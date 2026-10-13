# ocr-pro

Czysto javowy silnik OCR pod modele z rodziny PP-OCR. Jeden JAR i pliki modeli. Bez JNI, bez ONNX Runtime, bez OpenCV i bez systemowego Tesseract.

To nie jest ogólny runtime ONNX. Interpreter wykonuje grafy wykrywania i klasyfikacji obrotu z PP-OCRv5 mobile oraz rozpoznawanie PP-OCRv6 small. Po Fazie 1–2 wynik jest użyteczny na druku i prostych skanach, ale wolniejszy niż RapidOCR4j albo Tess4J i słabszy na trudnych dokumentach. Dojście do poziomu bibliotek komercyjnych wymaga lat heurystyk, nie samego runtime’u.

## Wymagania

- Java 21 (Temurin albo inny JDK z `jdk.incubator.vector`)
- Maven 3.9
- Modele, słownik znaków, listy słów i spis miast leżą w `models/`. Brakujące wagi RapidOCR silnik dociąga i sprawdza SHA-256.

Szybka ścieżka używa Vector API. Bez `--add-modules jdk.incubator.vector` silnik sam schodzi na kod skalarny (Java 8+ w sensie algorytmu; artefakt jest skompilowany na 21).

## Budowanie

```bash
mvn -DskipTests package
./ocr.sh selfcheck
```

`selfcheck` bez modułu wektorowego, żeby potwierdzić zapasową ścieżkę:

```bash
java -cp target/classes io.github.kamilkrzywanski.ocr.Cli selfcheck
```

Testy (mają dostęp do modułu wektorowego):

```bash
mvn -Dsurefire.useFile=false test
```

## Modele

Domyślny zestaw z RapidOCR / PaddleOCR (Apache-2.0):

- `ch_PP-OCRv5_det_mobile.onnx` — detekcja
- `PP-OCRv6_rec_small.onnx` — rozpoznawanie, wielojęzyczny CTC, w tym polski. Wejście ma wysokość 48
- `ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx` — obrót 0° / 180°, wejście 80×160
- `ppocrv6_dict.txt` — 18708 znaków, bez spacji; spację dokleja flaga `useSpaceChar` (klasa 18709, razem ze spacją i blankiem: 18710)

```bash
./ocr.sh download models
```

Pobranie idzie z ModelScope i sprawdza SHA-256. Istniejący plik o dobrym skrócie nie jest ściągany drugi raz, a zostawiony obok plik `.part` jest wtedy usuwany. Sam `OcrEngine` modeli nie pobiera.

Inny język: podmień model REC i słownik w `OcrConfig`. Głowa musi mieć tyle klas, ile `blank + znaki + opcjonalna spacja`.

## Użycie

```java
try (OcrEngine engine = OcrEngine.load(Path.of("models"))) {
    BufferedImage image = Images.read(Path.of("skan.jpg"));
    OcrResult result = engine.recognize(image);
    String text = result.text();
    for (TextLine line : result.lines()) {
        // line.text(), line.score(), line.detScore(), line.quad(), line.bounds()
    }
    for (TextTable table : result.tables()) {
        for (List<String> row : table.cells()) {
            // komórki od lewej; pusta komórka to ""
        }
    }
    List<OcrResult> pages = engine.recognizePdf(Path.of("scan.pdf"), 150f);
}
```

`quad` to osiem liczb: lewy górny, prawy górny, prawy dolny, lewy dolny, w pikselach obrazu. Jeden silnik nie jest współbieżny — `recognize` jest synchronizowane. Równoległe wywołania biorą silnik z `OcrPool`: każde miejsce to pełny silnik o tej samej konfiguracji, więc tekst jest ten sam co przy jednym silniku na tej konfiguracji. Zajęte miejsca każą czekać. `close` puszcza trwające wywołania i dopiero potem zamyka silniki. Silnika oddanego do `call` nie wolno zamykać, bo to miejsce wypada z puli. Jeden PDF zostaje na jednym silniku, razem ze swoim oknem stron. Po `close` kolejne wywołanie kończy się wyjątkiem, a sesje modelu są puszczane. PDF z wieloma stronami zrównolegla strony wewnątrz tego silnika, ale wtedy detekcja na stronie nie dokłada już pracy do tej samej puli wątków (inaczej pula zakleszczyłaby się sama ze sobą). Render idzie oknem o szerokości puli, więc w pamięci jest co najwyżej tyle bitmap, ile wątków. Jedna strona nadal liczy detekcję na pełnej puli.

```java
try (OcrPool pool = OcrPool.open(Path.of("models"), 2)) {
    OcrResult result = pool.recognize(image);
}
```

Jedna linia, bez detekcji:

```java
engine.recognizeLine(image);
```

CLI:

```bash
./ocr.sh ocr skan.png --limit 960
./ocr.sh bench skan.png
./ocr.sh pdf dokument.pdf --dpi 150
./ocr.sh convert models/ch_PP-OCRv5_det_mobile.onnx det.jir
./ocr.sh ops models/PP-OCRv6_rec_small.onnx --raw
./ocr.sh synth /tmp/faktura.png
./ocr.sh eval
./ocr.sh ocr skan.png --json
./ocr.sh pdf dokument.pdf --json
```

`ocr.sh` zostawia katalog, z którego zostało wywołane, więc względna ścieżka obrazu albo PDF jest liczona od tego miejsca. Gdy nie podano `--model`, skrypt sam podaje katalog `models` leżący obok niego. Tekst idzie na stdout, czasy na stderr. Czasy, CER i wynik `selfcheck` są zapisywane z kropką dziesiętną. Przy wielu stronach stdout dostaje nagłówek `--- page N ---` przed tekstem strony, gdy ta strona jest gotowa, a nie po całym pliku. Gdy strona ma siatkę z kresek, po tekście jest nagłówek `--- table N ---`, a komórki w wierszu rozdziela tabulator. Strona bez kresek nie dostaje tego nagłówka. `eval` kończy się kodem 1, gdy CER syntetycznej faktury przekroczy 0.35. Zły `--dpi`, `--limit` albo `--threads`, także zero i liczba ujemna, kończy się kodem 1 i nazwą flagi, zanim silnik otworzy model albo plik. Ten sam zapis działa ze znakiem równości, na przykład `--dpi=150`. Przełączniki `--no-cls`, `--no-det` i `--raw` nie pochłaniają następnej ścieżki, więc mogą stać przed nazwą pliku. `--no-cls=false` zostawia klasyfikator włączony. Nieznana flaga, na przykład `--thread`, oraz flaga bez wartości, na przykład samo `--model`, kończą się kodem 1, zanim otwarty zostanie plik. Ścieżka `--model`, która jest plikiem, kończy się komunikatem, że to nie jest katalog, zanim komenda otworzy obraz, PDF albo pobieranie. Puste `--model=` jest tym samym błędem co samo `--model`. Dodatkowy argument, na przykład drugi plik przy `ocr ocr` albo plik przy `eval`, kończy się komunikatem `unexpected argument`, zanim komenda cokolwiek otworzy. Powtórzona flaga, na przykład dwa razy `--limit`, też kończy się błędem, zanim plik zostanie otwarty. Flaga, której komenda nie używa, na przykład `--dpi` przy `ocr`, też kończy się błędem, zanim plik zostanie otwarty. Brak plików modelu kończy się komunikatem z `ocr download`. Przy `ocr` i `bench` ten komunikat pojawia się, zanim obraz zostanie odczytany. Pusty plik wagi jest zgłaszany tak samo, z nazwą etapu, zanim obraz zostanie odczytany.

`--json` przy `ocr` i `pdf` wypisuje jeden dokument JSON na stdout, zamiast tekstu i tabel TSV. Obraz to obiekt strony z kluczami `text`, `lines`, `tables`, zawsze i w tej kolejności. Linia ma `text`, `score`, `detScore`, `quad` (osiem liczb: lewy górny, prawy górny, prawy dolny, lewy dolny) i `bounds` (`x`, `y`, `width`, `height`). Tabela ma `bounds` i `cells`: wiersze napisów, pusta komórka to `""`. PDF to `{"pages":[...]}` w kolejności stron, z tym samym obiektem strony. Liczba, która nie jest skończona, jest `null`. Czasy zostają na stderr i nie wchodzą do dokumentu. `OcrJson.page` i `OcrJson.document` dają ten sam zapis co CLI. `--json` i `--json=true` włączają zapis, `--json=false` zostawia tekst. Przełącznik nie pochłania następnej ścieżki. Przy pozostałych komendach flaga jest odrzucana, zanim cokolwiek zostanie otwarte.

`Images.read` i CLI obracają JPEG według znacznika EXIF Orientation (wartości 1–8). Brak znacznika zostawia piksele bez zmian. PNG oraz `BufferedImage` podany wprost do `recognize` nie dostają tej korekty. TIFF albo GIF z więcej niż jednym obrazem kończy się komunikatem z tą liczbą. Komenda `ocr` czyta jeden obraz. Plik z nagłówkiem `%PDF-` w pierwszym kilobajcie, podany do `ocr ocr`, kończy się wskazaniem komendy `ocr pdf`, chyba że sam zaczyna się jako PNG, JPEG, GIF, BMP albo TIFF. Taki obraz jest czytany normalnie, nawet gdy napis `%PDF-` stoi w komentarzu. PNG, JPEG, GIF, BMP albo TIFF podany do `ocr pdf`, a także brak pliku, kończy się przed wczytaniem modelu. Brak pliku albo katalog podany do `ocr ocr` kończy się komunikatem, że to nie jest plik, zanim model zostanie wczytany. PDF z hasłem użytkownika kończy się komunikatem, że plik jest zaszyfrowany, zanim model zostanie wczytany. Gdy znacznik `/Encrypt` leży dalej niż 64 KiB od końca, preflight czyta obiekt wskazany przez `startxref`, więc długi strumień xref też zatrzymuje się przed modelem. PDF z pustym hasłem użytkownika otwiera się także wtedy, gdy hasło właściciela jest ustawione. Obraz, którego spakowany BGR nie mieści się w jednej tablicy Javy, kończy się komunikatem `image is too large`, także gdy iloczyn wymiarów przepełnia 64-bitowy licznik.

## Co jest w środku

```
BufferedImage
  → BGR, resize half-pixel, normalizacja (x/255 - 0.5) / 0.5
  → DET
  → progi DB, prostokąt o minimalnym polu, unclip
  → crop (albo obrót o 90° w lewo, gdy linia jest pionowa)
  → CLS 80×160, ewentualnie obrót o 180°
  → REC wysokość 48, CTC greedy
  → opcjonalny leksykon, gdy obok modeli leżą oba spisy słów
  → opcjonalnie drugi odczyt bez cyfry Lp sklejonej kreską, gdy zgadza się z pierwszym
  → opcjonalnie drugi odczyt kodu pocztowego przy progu 220, gdy w mieście została cyfra
  → opcjonalnie kropka nad wielkim Z z luką w górnej kresce i drugi odczyt, gdy zmienia się tylko ten znak
  → opcjonalnie urzędowa pisownia miasta, gdy cała linia jest kodem pocztowym z jedną nazwą
  → bez leksykonu drugi odczyt z białym marginesem, gdy dochodzi tylko ł albo spacja między literami
  → etykieta faktury VAT, gdy cała linia jest o jedną edycję od jednej znanej etykiety
  → kolejność czytania
  → siatka z kresek, osobno od tekstu
```

Kolejność czytania składa wiersz, gdy pionowe zachodzenie z pierwszym pudełkiem wiersza sięga co najmniej połowy krótszego pudełka. Pionowy napis dostaje własny wiersz. Dwie kolumny tekstu oddzielone rynną czyta się od góry do dołu, najpierw lewą, potem prawą. Cena wyrównana do prawego marginesu zostaje przy towarze. Siatka o trzech i więcej kolumnach jest czytana wierszami.

Siatka z kresek, od dwóch kolumn i dwóch wierszy, jest osobnym wynikiem `tables()`. Pusta komórka to pusty napis. Pudełko przecięte kreską w środku jest czytane jeszcze raz, kawałek po kawałku, bez ponownego obrotu, więc napis w komórce może różnić się od linii. Dwie długie części tej samej kreski, między którymi jest osiemnaście białych wierszy, zostają jedną kreską. Krótszy kawałek, na przykład trzonek cyfry, tak się nie skleja, bo inaczej stawki VAT rozpadają się na cyfry. Pudełko, które zachodzi na kreskę mniej niż 28 pikselami, nie jest cięte: tyle nachodzi „Szt.” na kreskę między liczbą a j.m., a odczyt tego paska jest fałszywą cyfrą. Gdy ta kreska wchodzi w pudełko o co najmniej 20 pikseli, wnętrze komórki jest czytane jeszcze raz i zostaje tylko wtedy, gdy napis różni się wyłącznie wielkością liter. Na fakturze komórka j.m. dostaje wtedy „szt.” zamiast „Szt.”. Linia zostaje przy pierwszym odczycie. Numer wiersza przy nazwie ma 36 pikseli i nadal jest odcinany. Pusta komórka, w której został tusz, jest czytana raz i zostaje tylko wtedy, gdy to krótka liczba albo krótkie słowo. Na fakturze wraca tak jedynka w kolumnie Liczba i nagłówek Lp, których detektor nie złapał. Kreska ani pojedynczy obcy znak nie wchodzą. Cena wyrównana do marginesu nie tworzy tabeli. Tabela bez kreski zostaje zwykłym tekstem.

Format wewnętrzny to graf plus wagi. ONNX czyta mały parser protobuf (bez biblioteki protobuf i bez Pythona). Po optymalizacji można zapisać `JIR1` i ładować go szybciej. JIR nie optymalizuje drugi raz. Ucięty albo niespójny plik JIR kończy się wyjątkiem `OcrException`.

Optymalizacje grafu: wycięcie Identity, złożenie Conv+BatchNorm, złożenie biasu z Add, epilog ReLU / Sigmoid / HardSwish. Skalarne mnożenie i dodawanie tuż po konwolucji wchodzi w jej wagi. Drugi taki affine, już po aktywacji, zostaje w tym samym epilogu (`skala * aktywacja + przesunięcie`). Konwolucja 1×1 idzie jako GEMM: cztery wiersze naraz, a panel dwóch wektorów wyjścia zostaje w rejestrach przez całą redukcję K. Przy jednym wątku ten panel kolumn aktywacji jest najpierw zbierany do zwartego bufora, bo te same kolumny wracają raz na każde cztery wiersze wag, a kolejne wiersze aktywacji dzieli długi skok. Rozpoznawanie liczy każdą linię jednym wątkiem, więc z tego korzysta. Detekcja dzielona na wątki czyta aktywację bezpośrednio. 3×3 o skoku 1 składa całe okno w jednym przejściu po wierszu wyjścia. 3×3 o skoku 2 pakuje wiersz na piksele parzyste i nieparzyste, po czym też składa całe okno w jednym przejściu. 5×5 i pozostałe jądra liczone są wprost, bez im2col. Najbliższy resize o całkowitej skali (2, 4, 8) powiela bloki. ConvTranspose 2×2 ze skokiem 2 zapisuje sąsiednią parę wyjść w jednym przejściu po wejściu. Mapy elementarne od miliona elementów są dzielone na wątki. Int8 jest zaimplementowane i wyłączone: w czystej Javie zwykle nie przyspiesza i psuje dokładność.

Preprocessing celuje w PP-OCR: kanały BGR, średnia i odchylenie 0.5, padding rozpoznawania zerami dopiero po normalizacji (szarość 127.5). Deskew i próg Sauvoli są w kodzie i domyślnie wyłączone — na modelu neuronowym często szkodzą.

Detekcja: próg 0.35, próg pudełka 0.5, unclip 1.6, dylatacja 2×2, dłuższy bok do 960, potem zaokrąglenie do wielokrotności 32 (`Math.round`, half up). Pythonowe `round` half-to-even potrafi zmienić bok o 32 piksele względem Javy. Po obrocie linii, a przed skalowaniem do wysokości 48, lżejsze pasmo tuszu odpada, gdy od cięższego dzieli je co najmniej 20 białych wierszy. Krótsza przerwa zostaje. Gdy po lewej stronie kadru stoi wąska cyfra, przerwa i cienka kreska, a pierwszy odczyt zaczyna się od tej cyfry, kadr jest czytany drugi raz bez niej. Drugi napis zostaje, gdy jest pierwszym bez tej cyfry i spacji albo, bez słownika, gdy krótszy odczyt zgubił tylko ł albo ó. Zostaje wtedy pierwszy napis bez cyfry. Komórki tabeli tego kroku nie przechodzą. Gdy linia zaczyna się od kodu pocztowego, a reszta wciąż zawiera cyfrę, kadr jest czytany drugi raz po progu jasności 220: szary tusz staje się czarny, papier biały. Drugi napis zostaje tylko wtedy, gdy kod się nie zmienia, a miasto ma co najmniej trzy znaki i żadnej cyfry. Komórki tabeli tego kroku nie przechodzą.

Gdy w linii jest wielkie Z, a górna kreska tej litery ma w środku lukę, nad nią dochodzi czarna kropka 5×5 i kadr jest czytany drugi raz. Nowy napis zostaje tylko wtedy, gdy jedyną różnicą jest ż albo ź na tym Z. Wielkość litery zostaje z pierwszego odczytu. Bez luki kropka nie powstaje. Komórki tabeli tego kroku nie przechodzą.

Gdy obok modeli leży `postal-pl.txt.gz`, a cała linia jest kodem pocztowym i nazwą miejscowości, nazwa dostaje urzędową pisownię. Kod musi mieć w liście jedną nazwę, litery muszą już się zgadzać po zdjęciu ogonków, a lista nie może zabrać znaku, który odczyt już ma. Spacje przy kodzie zostają. Kody wspólne dla kilku miejsc nie wchodzą do listy. Plik jest zrzutem kodów GeoNames dla Polski (CC-BY-4.0, https://www.geonames.org/). Bez niego odczyt się nie zmienia. Komórki tabeli tego kroku nie przechodzą.

Gdy w katalogu modeli są `lexicon-pl.txt.gz` i `lexicon-en.txt.gz`, token, który nie jest słowem polskim ani angielskim, dostaje jedyną bliską formę ze słownika polskiego. Po ogonkach, „t” czytanym jako „ł” i „i” czytanym jako „j” dochodzi jeszcze ó, e albo o czytane jako ć, samo lub razem z s czytanym jako ś, a potem najwyżej dwa l czytane jako i albo j i pojedyncze o czytane jako c. Token pisany samymi wielkimi literami dostaje formę tylko wtedy, gdy od jednego słowa dzieli go wstawiona litera i jeden ogonek. Krok zostawia token, gdy pasuje więcej niż jedno słowo. Cyfra sklejona ze znanym słowem dostaje spację. Na fakturze wraca przez to Wartość, Liczba, stawki, Podpis, upoważnionej, odbioru, „2 Wysyłka” i napis ORYGINAŁ w miejscu ORYGNAL. SPRZEDAZ zostaje, bo tak samo blisko jest kilka słów. Skrót polędw jest w tym słowniku zawsze, bo sjp ma tylko polędwicę, a na paragonie nazwa urywa się po w. Bez tych dwóch plików sam leksykon nic nie zmienia. Kawałek przecięty kreską tabeli dostaje ten sam leksykon, więc nazwa w komórce nie gubi ł, które linia już ma. Pozostałe kroki komórek nie ruszają.

Jedna spacja między nawiasem otwierającym a pojedynczą literą, którą ten nawias od razu zamyka, odpada. Na fakturze „Wystawił( a):” i „Odebrał(a):” są tym samym drukiem, a odstęp zostaje tylko w pierwszym. Szersza przerwa, jak „( a )”, zostaje. Komórki tabeli tego kroku nie przechodzą.

Gdy cała linia to kropka, jedna litera i kropka, kadr jest czytany drugi raz, szerszy o 16 pikseli w lewo i 16 w dół. Góra kadru zostaje, bo kreska tabeli czyta się wtedy jako cyfra. Nowy napis zostaje tylko wtedy, gdy jest tym samym tekstem z jedną literą doklejoną przed kropką. Na fakturze nagłówek `j.m.` wraca z `.m.`. Komórki tabeli tego kroku nie przechodzą.

Gdy linia zaczyna się od `[`, `]` albo `|` albo tak się kończy, wysokie ciemne kolumny w skrajnych 16 pikselach kadru są bielone i kadr jest czytany drugi raz. Nowy napis zostaje tylko wtedy, gdy od pierwszego różni się wyłącznie taką jedną kreską z lewej i jedną z prawej. Na fakturze `[VAT (%)|` wraca jako `VAT (%)`. Inna różnica zostawia pierwszy odczyt. Komórki tabeli tego kroku nie przechodzą.

Linia, która jest kwotą i krótką stawką, jak `8.13 23`, jest rozcinana na pionowej kresce między nimi. Zostają dwie linie tylko wtedy, gdy lewy kawałek czyta się z powrotem jako kwota, a prawy jako stawka. Inaczej zostaje jedna linia. Na fakturze to pudełko daje osobno `8.13` i `23`.

Gdy spisów słów nie ma, a linia zawiera t albo l i nie jest sama kodem pocztowym, kadr jest czytany jeszcze trzy razy: z czterema białymi wierszami nad i pod nim, z tuszem przesuniętym o cztery piksele w dół i z ośmioma białymi wierszami pod spodem. Nowy napis zostaje tylko wtedy, gdy jedyną różnicą jest ł zamiast t lub l albo spacja wstawiona między dwie litery. Takich spacji mogą być najwyżej dwie. Zostaje odczyt, który takich poprawek ma najwięcej. Na fakturze bez słownika wraca spacja w „i magazyn”, ł w usługi oraz cała Spółka, razem ze spacją przed Wspólnicy. Ó w tej Spółka dokłada osobno drugie miejsce CTC, gdy „ó” jest drugie i ma co najmniej 0,05. Wielkie „S”, którego drugie miejsce to „Ś” o wartości co najmniej 0,015, jest czytane jako Ś także bez słownika, i to przywraca Świat na paragonie. Małe „s” tą regułą nie przechodzi. Inna różnica marginesu, także zgubione ł albo znak mnożenia, zostawia pierwszy odczyt. Ze słownikiem krok marginesu się nie wykonuje. Komórki tabeli tego kroku nie przechodzą.

Etykieta bez cyfry, która jest o jedną edycję od dokładnie jednej znanej nazwy pola faktury VAT, dostaje tę nazwę. Dwukropek na końcu zostaje. Linia, która już jest taką nazwą, która zawiera cyfrę albo która jest równie blisko dwóch nazw, zostaje. Na fakturze bez słownika wracają w ten sposób Miejsce wystawienia i Słownie. Komórki tabeli tego kroku nie przechodzą.

## Wydajność

Cel z planu to poniżej 300–500 ms na stronę A4 przy Vector API. Detekcja dostaje dłuższy bok 960, a rozpoznawanie rośnie z liczbą i długością linii. Tabela faktury i pomiar `./ocr.sh bench` poniżej są z latin PP-OCRv5 rec, sprzed podmiany na PP-OCRv6 small. Przy tamtym modelu krótka faktura mieściła się w progu już przy jednym wątku.

Pomiar na syntetycznej fakturze 1200×700 (pięć linii, limit detekcji 960), Temurin 21.0.11, Vector API włączone, latin PP-OCRv5 rec. Jedna rozgrzewka i 10 przebiegów w tym samym procesie:

| Wątki | minimum | mediana |
| --- | ---: | ---: |
| 24 (tyle, ile rdzeni logicznych) | 188 ms | 198 ms |
| 8 | 200 ms | 218 ms |
| 4 | 222 ms | 238 ms |
| 1 | 398 ms | 403 ms |

Tabela jest sprzed zbierania panelu. Sparowany pomiar tej samej faktury, stary jar i nowe klasy na przemian: przy 24 wątkach mediany 201 ms i 200 ms bez zbierania, 196 ms i 189 ms ze zbieraniem. W parze, w której nowy kod szedł pierwszy, suma rozpoznawania przy jednym wątku miała medianę 183 ms wobec 195 ms.

`./ocr.sh bench` na tej fakturze i na tamtym jarze z latin PP-OCRv5 rec (jedna rozgrzewka i trzy przebiegi) dał minimum 181 ms i medianę 217 ms. Trzy przebiegi nie wystarczają, żeby trafić w medianę z dłuższej serii. Jeden wątek bez wektorów jest jeszcze wolniejszy: `selfcheck` bez `--add-modules jdk.incubator.vector` schodzi na ścieżkę skalarną i dalej liczy poprawnie (`vector=false`, wynik 11).

Tablice aktywacji dłuższe niż 256 elementów dostają pojemność równą potędze dwójki i wracają do wspólnego kubełka, więc kształty z tej samej potęgi dzielą bufor.

Gęsta strona 1240×1754 (150 dpi, 31 linii, DejaVu 32 px, limit detekcji 960, próg detekcji 0.3), Temurin 21.0.11, Vector API, 24 wątki, cztery rozgrzewki i osiem przebiegów, rozpoznawanie PP-OCRv6 small. Minimum 631 ms, mediana 658 ms, 31 z 31 linii, CER 0,031. Cały ten CER to podwójne spacje wzorca; po złożeniu białych znaków zostaje 0. `Długopis` i `o.o.` są wierne. Mediana strony jest powyżej progu 500 ms. W tych ośmiu przebiegach rozpoznawanie trwało 468–508 ms, a detekcja 159–185 ms. Ten sam protokół przy latin PP-OCRv5 rec: minimum 288 ms, mediana 307 ms, CER 0,038, a z liter zostawały `Długopis` → `Dtugopis` oraz `o.o.` → `0.o.` / `0.0.`. Jar sprzed klas rozmiaru, nadal na tamtym modelu: minimum 312 ms, mediana 327 ms. Po dwóch przejściach tamtego jara wolne listy trzymały 161 MB w detekcji, 217 MB w 24 sesjach rozpoznawania i 16 MB w klasyfikatorze kąta. Log GC tamtego przebiegu: sterta do 1,8 GB, suma pauz 99 ms, najdłuższa 22 ms. Log przebiegu jeszcze wcześniejszego jara: sterta do 4,6 GB, suma pauz 277 ms, najdłuższa 33 ms.

Ta sama strona przy czcionce 22 px ma 47 linii. Limit 960 znajduje wszystkie, CER 0,027. Po złożeniu białych znaków zostaje 0.

Krótka faktura DejaVu (pięć linii) ma CER 0, łącznie z pangramem `Zażółć gęślą jaźń`. Linia z samych wielkich liter z ogonkami oraz linia złożona z samych ogonków nadal gubią część znaków. Strona PDF z Helveticą (`FAKTURA 2024`, `NIP 1234567890`) jest czytana w całości. Pionowa linia `FAKTURA` jest obracana i czytana.

## Testy

- operatory względem wartości liczonych ręcznie (błąd około 1e-4)
- parser oficjalnych ONNX: liczby operatorów, wejście CLS 80×160, wejście REC o wysokości 48, wyjście REC 18710
- detekcja 64×64: graf surowy i po fuzji, różnica poniżej 1e-3, wartości skończone
- faktura narysowana DejaVu (albo SansSerif): CER poniżej 0.25, w tekście są `FAKTURA`, `NIP` i `Zażółć gęślą jaźń`, a lista tabel jest pusta
- pusta kartka 64×64, obraz 1×1 i `null` (wyjątek)
- pionowa linia `FAKTURA`
- strona PDF (Helvetica, sam ASCII) renderowana przez PDFBox
- kilka stron PDF w kolejności oraz pusty PDF
- JPEG: EXIF Orientation 1–8; brak znacznika zostawia piksele
- trzy skany druku (list Nixona, paragon Biedronki, pola faktury Rewasza poza tabelą): liczba błędów znaków nie rośnie ponad próg zapisany przy PP-OCRv6 small. Podpis, pieczątka i siatka tabeli nie wchodzą do liczenia. Złączony tekst faktury ma kolumnę sprzedawcy przed kolumną nabywcy, a cena na paragonie zostaje w wierszu towaru. Na fakturze Rewasza nazwa towaru i kwota brutto są w różnych komórkach jednego wiersza, a stawki 23% i 5% są w jednej siatce. Paragon i list mają pustą listę tabel. Bez katalogu `models/` ten test jest pomijany

## Zakres, którego v1 nie obiecuje

Pismo ręczne, tabela bez kreski, gazeta o trzech kolumnach i jakość na poziomie Aspose.OCR na trudnych skanach. Pionowy tekst jest obracany. Osobny model REC można podłączyć przez `OcrConfig`, razem ze słownikiem o tylu klasach, ile ma głowa.
