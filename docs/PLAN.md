# Plan: czysto javowy OCR

Dokument decyzji. Kod, nazwy i CLI są po angielsku. Ten plik jest po polsku.

## Cel

Silnik OCR bez natywnych bibliotek, w jednym JAR-ze plus pliki modeli. Ma czytać druk, faktury i zwykłe skany. Cel wydajności: poniżej 300–500 ms na stronę A4 na CPU przy Vector API. To jest próg do zmierzenia, nie gwarancja. Pomysł jest zbliżony do lw.PPOCR (własny runtime pod modele PP-OCR). Kod jest napisany od zera i nie jest portem tamtych źródeł.

Po Fazach 1–2 silnik działa, ale jest wolniejszy i na trudnych dokumentach słabszy niż RapidOCR4j albo Tess4J. Przewaga to brak natywnych zależności i pełna kontrola. Poziom bibliotek komercyjnych na ciężkich skanach to lata modeli i heurystyk.

## Faza 0 — decyzje

Zakres v1:

- Tylko druk. Pismo ręczne nie ma osobnej ścieżki.
- Detekcja, opcjonalny klasyfikator obrotu o 180° i rozpoznawanie. Brak struktury tabel. Kolejność czytania grupuje linie, gdy pionowe zachodzenie podzielone przez mniejszą wysokość jest co najmniej 0.5 wobec dowolnego pudełka już będącego w wierszu. Potem sortowanie wiersza po x. Dobre dla faktur. Gazeta wielokolumnowa może przepleść kolumny.
- Język startowy: detekcja i klasyfikator kąta z PP-OCRv5 mobile, rozpoznawanie PP-OCRv6 small (wielojęzyczny CTC, polski jest w słowniku). Inny REC to podmiana ONNX i słownika. PP-OCRv6 small zastąpił latin PP-OCRv5, bo na tym samym druku DejaVu czyta polskie znaki, które tamten model gubił. Wariant medium ma tyle samo całych linii bez błędu i znacznie większy plik, więc nie jest domyślny. Tiny i latin v3 zostały zmierzone i są słabsze.
- Java 21. Ścieżka skalarna działa zawsze. Vector API (`jdk.incubator.vector`) włącza się tylko, gdy JVM dostanie `--add-modules jdk.incubator.vector`. Brak `module-info`, które twardo wymagałoby inkubatora. Jedyny import inkubatora to `VectorKernels`. Reszta ładuje tę klasę przez refleksję i przy braku modułu zostaje przy skalarze.
- Model: parser podzbioru protobuf czyta oficjalny ONNX do grafu wewnętrznego, potem optymalizacja, opcjonalnie binarny JIR v1 (magia `JIR1`) do szybszego startu. Wczytanie JIR nie optymalizuje drugi raz. Kształty są dynamiczne. To nie jest ogólny ONNX Runtime.
- Metryki: testy operatorów, CER (Levenshtein) i czas strony (1 rozgrzanie + 3 przebiegi, minimum i mediana). Progu 500 ms nie ogłaszamy przed pomiarem.
- Limit detekcji: dłuższy bok 960, do ustawienia. 736 jest praktycznym trybem szybkim, 1280 i 1536 trybem jakości. Zaokrąglenie boku do 32 używa `Math.round` (half up). Pythonowe half-to-even potrafi różnić się o 32 piksele.
- Int8 jest opcjonalne i wyłączone. Skala wag per wiersz, skala aktywacji per tensor. Testowane jednostkowo. Nie jest ścieżką dokładności. Włącza się tylko dla GEMM 1×1, gdy `quantize` jest prawdziwe.
- PDF przez Apache PDFBox 3.0.5, wciągnięty do shaded JAR. Helvetica nie ma polskich glifów, więc próbka PDF jest ASCII. Polski tekst rysuje Java2D fontem DejaVu.
- Preprocessing jak PP-OCR / OpenCV: BGR (BufferedImage jest RGB), mean/std 0.5, czyli `(x/255 - 0.5) / 0.5`. Resize dwuliniowy z półpiksela OpenCV: `src = (dst + 0.5) * scale - 0.5`. Wysokość REC 48, minimalna szerokość 320, sufit około 4096. Padding po prawej jest zerem już w tensorze znormalizowanym (szarość 127.5).
- CLS tego modelu ma stałe 80×160, nie stare 48×192. Rozmiar czytamy z sygnatury modelu.
- DB bliżej RapidOCR: próg 0.3, próg pudełka 0.5, unclip 1.6, do 1000 kandydatów, dylatacja 2×2 z kotwicą OpenCV `(0, 0)` (rozszerzenie w stronę lewego górnego rogu), score mode fast, limit typu max. Budowa konfiguracji odrzuca niefinity progi, limit detekcji poniżej 1, typ limitu inny niż `max` albo `min`, `maxCandidates` poniżej 1 oraz tensor REC, który nie mieści się w tablicy.
- Deskew i próg Sauvoli (k=0.2, r=128, okno około 25) są zaimplementowane i domyślnie wyłączone.
- Pionowy crop (`wysokość >= 1.5 * szerokość`) dostaje obrót o 90° w lewo przed CLS i REC. Kolejność: warp, ewentualny obrót 90°, CLS, ewentualny obrót 180°, REC.
- CTC greedy: blank na indeksie 0, zwijanie powtórzeń, wynik to średnia prawdopodobieństw zostawionych kroków. Drugi softmax nie jest nakładany, gdy wyjście już jest rozkładem.
- Wyjście REC tego modelu to `[N, T, 18710]`. Wejście ma stałą wysokość 48, więc inna `recHeight` jest odrzucana przy starcie. Oś klas jest tą, której rozmiar równa się słownikowi. Mapa detekcji dostaje sigmoid, gdy wartości wychodzą poza około `[0, 1.2]`.
- Słownik: 18708 tokenów, każdy w osobnej linii, bez linii ze spacją. Puste linie są pomijane, treści znaku się nie przycina, zdejmowany jest jeden końcowy CR i BOM. `useSpaceChar` dokleja spację. Klasy: blank + 18708 + spacja = 18710. Spacja ma indeks 18709. Polskie znaki `ĄąĘęŁłŃńÓóŚśŹźŻż` są w pliku.
- Jeden `OcrEngine` nie jest używany równolegle z zewnątrz. Wewnętrzna pula zrównolegla linie REC. GEMM detekcji może dzielić wiersze na puli wołającego wątku. Detekcji nie wolno wołać z wątku puli. REC w workerze ma `pool = null` i jeden wątek. Strony PDF przy wielu wątkach idą osobno, każda ze swoim `fork()` bez puli, żeby worker nie oddawał pracy do tej samej puli.
- API: `OcrEngine.load` / `create`, `recognize(BufferedImage)` → tekst i lista linii (score, score detekcji, quad, bounds), `recognizePdf`, `recognizeLine`. CLI: `download`, `ocr`, `bench`, `pdf`, `convert`, `ops`, `synth`, `eval`, `selfcheck`.

Modele bazowe (nie są w gicie, skróty SHA-256 są w `ModelFiles`):

| Plik | Rola |
| --- | --- |
| `ch_PP-OCRv5_det_mobile.onnx` | detekcja, opset 14 |
| `PP-OCRv6_rec_small.onnx` | rozpoznawanie, opset 11, wejście wysokość 48, 18710 klas |
| `ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx` | obrót, opset 9, wejście 80×160, 2 klasy |
| `ppocrv6_dict.txt` | słownik wielojęzyczny, w tym polski |

Operatory, które muszą działać: Identity, Add, Sub, Mul, Div, Pow, Sqrt, Erf, Conv (grupy, bez biasu i z biasem), ConvTranspose 2D, Relu, Sigmoid, HardSigmoid (alpha, beta), HardSwish, GlobalAveragePool, AveragePool, MaxPool (także `auto_pad` SAME_UPPER), BatchNormalization inferencyjne, Resize nearest, Concat, MatMul, Softmax zależny od opsetu, ReduceMean, Squeeze, Unsqueeze, Transpose, Slice (atrybuty albo wejścia, także ujemny krok), Reshape, Shape. Dodatkowo Constant, Cast, Flatten i Dropout jako przejście. GELU w PP-OCRv6 jest złożone z Erf, nie jest osobnym operatorem. Nieznany operator rzuca `Unsupported op: NAZWA` razem z kształtami wejść.

## Faza 1 — runtime

Zrobione w pakiecie `org.krzywanski.ocr.runtime`:

- parser ONNX i zapis/odczyt JIR. Wymiar wagi albo sygnatury, który jest ujemny albo nie mieści się w `int`, jest odrzucany, także gdy obcięcie do 32 bitów wygląda na zero albo na mały bok. Symboliczny `dim_param` zostaje wymiarem dynamicznym. Opset spoza zakresu `int` też jest odrzucany, zamiast stać się małym numerem opsetu.
- optymalizacja grafu (Identity, Conv+BN, bias Add, epilog aktywacji, rozłożony HardSwish)
- arena buforów bez zerowania przy alokacji; wagi są tylko do odczytu i współdzielone między sesjami
- GEMM skalarny i wektorowy, konwolucja 1×1 jako GEMM, pozostałe konwolucje wprost
- sesja `fork()` dla workerów: własna arena, wspólny graf

## Faza 2 — pre i post

Zrobione:

- `Images`: BGR, resize, normalizacja, tensor REC i CLS, obroty, deskew, Sauvola. Iloczyn bajtów BGR, który nie mieści się w `int`, jest odrzucany także wtedy, gdy mnożenie przepełnia `long` i wygląda na mały rozmiar. Bok detekcji po zaokrągleniu do 32, który nie mieści się w `int`, też jest odrzucany. Obrót, warp i Sauvola przy boku zero wracają od razu, bez pętli po drugim wymiarze.
- `Geometry`: kadłub wypukły, prostokąt o minimalnym polu, score wielokąta, homografia
- `DbPost`, `Ctc`, `Charset`, `ReadingOrder`. Mapa detekcji, której iloczyn boków nie mieści się w buforze, jest odrzucana także wtedy, gdy mnożenie przepełnia `int` i wygląda na pustą mapę.
- `OcrEngine` spina pipeline

## Faza 3 — użyteczność

Zrobione w v1:

- wymiana modelu REC przez konfigurację. Ścieżka katalogu modeli, która jest plikiem albo jest pusta, jest odrzucana, zanim CLI otworzy obraz, PDF albo pobieranie. Brakujące wagi przy `ocr` i `bench` są zgłaszane, zanim obraz zostanie odczytany. Pusty plik wagi też, z nazwą etapu, zanim parser zobaczy obcięty protobuf. Gdy skrót wagi już się zgadza, osierocony plik `.part` jest usuwany i nic nie jest pobierane. Dodatkowy argument pozycji, powtórzona flaga i flaga, której komenda nie używa, też kończą się błędem, zanim komenda cokolwiek otworzy
- deskew i próg adaptacyjny jako opcje, domyślnie wyłączone
- PDFBox: render strony, potem ten sam OCR. Wiele stron idzie oknem o szerokości puli: w pamięci jest co najwyżej tyle bitmap, ile wątków. Jedna strona używa pełnej puli detekcji. CLI wypisuje stronę, gdy jest gotowa; nagłówek `--- page N ---` jest tylko przy więcej niż jednej stronie. Numer strony spoza zakresu jest błędem OCR, liczonym od 1. PDF z hasłem użytkownika jest odrzucany, zanim CLI wczyta model. Gdy znacznik `/Encrypt` leży dalej niż 64 KiB od końca, preflight czyta obiekt wskazany przez `startxref`, więc długi strumień xref też zatrzymuje się przed modelem. PDF z pustym hasłem użytkownika otwiera się także przy ustawionym haśle właściciela.
- API `recognize` / `recognizeLine` / `recognizePdf`
- CLI, syntetyczna faktura, CER, porównanie czasów zostaje lokalnym pomiarem (Tess4J / RapidOCR4j nie są zależnością). `ocr.sh` zostawia katalog wywołania, więc względna ścieżka pliku jest liczona od niego. Gdy nie podano `--model`, skrypt podaje katalog `models` obok siebie
- `Images.read` i CLI obracają JPEG według EXIF Orientation. PNG oraz obraz już w pamięci nie są ruszane. TIFF i GIF z więcej niż jednym obrazem są odrzucane z liczbą obrazów. Brak pliku i katalog kończą się komunikatem, że to nie jest plik. Nagłówek PNG, JPEG, GIF, BMP albo TIFF wygrywa z napisem `%PDF-` w pierwszym kilobajcie, więc taki plik nie jest odsyłany do `ocr pdf`.

## Faza 4 — wydajność

Zrobione w v1: Vector API na GEMM, AXPY i aktywacjach, wielokrotne użycie buforów, klasy rozmiaru dla tablic aktywacji, pula linii, równoległe strony PDF bez zagnieżdżania w tej samej puli, haczyk int8 wyłączony domyślnie.

Nieobiecane: dogonienie ONNX Runtime na konwolucji. Zmierzony czas strony, z protokołem, jest w README.

## Ryzyka

- GEMM i konwolucja w czystej Javie zostają wolniejsze niż natywny runtime.
- Pełna lista operatorów ONNX nie jest celem. Nowy eksport modelu może nazwać brakujący operator i wtedy dopiero go dopisujemy.
- Jakość zależy od modelu i preprocessingu bardziej niż od kolejnych operatorów.
- Czarny tekst na białym tle nie wykryje zamiany BGR/RGB. Kanały i tak trzymamy w BGR, tak jak wagi.

## Kryterium „działa”

- testy operatorów: błąd bezwzględny około 1e-4 wobec wartości ręcznych
- czysty druk łaciński i polski: CER poniżej około 0.25
- harness czasu drukuje milisekundy i nie deklaruje progu, dopóki pomiar nie jest w README
