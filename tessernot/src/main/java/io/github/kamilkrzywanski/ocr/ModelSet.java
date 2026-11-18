package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.post.Charset;
import io.github.kamilkrzywanski.ocr.internal.post.Lexicon;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;
import io.github.kamilkrzywanski.ocr.internal.post.RunnerUps;
import io.github.kamilkrzywanski.ocr.internal.post.Vocabulary;
import io.github.kamilkrzywanski.ocr.internal.runtime.Graph;
import io.github.kamilkrzywanski.ocr.internal.runtime.Session;
import io.github.kamilkrzywanski.ocr.internal.runtime.Sig;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a configuration loads and nothing changes afterwards: the optimized graphs with their
 * weights, the charset, and the word lists and postal codes of the corrections. An engine runs
 * its own sessions over it, and the engines of an {@link OcrPool} share one, so a pool holds the
 * weights and the four million words of the Polish list once, not once per engine.
 */
final class ModelSet {
    /** Null when the configuration turns detection off. */
    final Graph det;
    /** Null when the configuration turns recognition off; then the fields below are null too. */
    final Graph rec;
    /** Null when the configuration turns the line classifier off. */
    final Graph cls;
    final Charset charset;
    /** The runner-up rules of {@link Corrections#POLISH} for the charset; null without corrections. */
    final RunnerUps runnerUps;
    final Lexicon lexicon;
    /** Lists for the CTC word-list search: the user's, plus the Polish profile's own. Null when there is none. */
    final Vocabulary words;
    final PostalCities postal;

    private ModelSet(Graph det, Graph rec, Graph cls, Charset charset, RunnerUps runnerUps, Lexicon lexicon,
                     Vocabulary words, PostalCities postal) {
        this.det = det;
        this.rec = rec;
        this.cls = cls;
        this.charset = charset;
        this.runnerUps = runnerUps;
        this.lexicon = lexicon;
        this.words = words;
        this.postal = postal;
    }

    /** Reads, checks and optimizes every model file the configuration uses. */
    static ModelSet load(OcrConfig cfg) {
        boolean corrected = cfg.corrections() == Corrections.POLISH;
        Graph det = null;
        if (cfg.useDet()) {
            ModelFiles.require(cfg.detModel(), "detection model");
            det = optimized(graph(cfg.detModel()));
        }
        Graph rec = null;
        Charset charset = null;
        RunnerUps runnerUps = null;
        Lexicon lexicon = null;
        Vocabulary words = null;
        PostalCities postal = null;
        if (cfg.useRec()) {
            ModelFiles.require(cfg.recModel(), "recognition model");
            ModelFiles.require(cfg.dict(), "recognition dict");
            charset = charset(cfg.dict(), cfg.useSpaceChar());
            rec = graph(cfg.recModel());
            if (cfg.alphabet() == Alphabet.LATIN) {
                int[] keep = charset.latin();
                narrowHead(rec, charset.size(), keep);
                charset = charset.subset(keep);
            }
            rec = optimized(rec);
            runnerUps = corrected ? RunnerUps.polish(charset) : null;
            // The word lists belong to the Polish corrections. Without them the files are not read.
            // They sit next to the dict on disk, or else in a bundle (tessernot-models-pl).
            lexicon = corrected ? lexicon(cfg.dict()) : null;
            postal = corrected ? postal(cfg.dict()) : null;
            words = vocabulary(cfg.wordLists(), lexicon);
        }
        Graph cls = null;
        if (cfg.useCls()) {
            ModelFiles.require(cfg.clsModel(), "classifier model");
            cls = optimized(graph(cfg.clsModel()));
        }
        return new ModelSet(det, rec, cls, charset, runnerUps, lexicon, words, postal);
    }

    /** Optimized here, once and on one thread: afterwards the graph is only read, by every session. */
    private static Graph optimized(Graph graph) {
        if (!graph.optimized) {
            graph.optimize();
        }
        return graph;
    }

    private static Graph graph(ModelSource source) {
        try {
            return Session.read(source.read(), source.toString(), source.directory());
        } catch (IOException e) {
            throw new OcrException("cannot load " + source + ": " + e.getMessage(), e);
        }
    }

    private static Charset charset(ModelSource source, boolean useSpaceChar) {
        try {
            return Charset.load(source.read(), source.toString(), useSpaceChar);
        } catch (IOException e) {
            throw new OcrException("cannot read dict " + source + ": " + e.getMessage(), e);
        }
    }

    /** Both gzip word lists next to the dict or in a bundle, or null when either one is absent. */
    private static Lexicon lexicon(ModelSource dict) {
        ModelSource polish = dict.sibling(Lexicon.POLISH_FILE);
        ModelSource english = dict.sibling(Lexicon.ENGLISH_FILE);
        if (polish == null || english == null) {
            return null;
        }
        return Lexicon.read(wordList(polish), polish.toString(), wordList(english), english.toString());
    }

    private static Vocabulary vocabulary(List<ModelSource> sources, Lexicon lexicon) {
        List<Vocabulary> lists = new ArrayList<>();
        for (ModelSource source : sources) {
            ModelFiles.require(source, "word list");
            try {
                lists.add(Vocabulary.read(source.read(), source.toString()));
            } catch (IOException e) {
                throw new OcrException("cannot read word list " + source + ": " + e.getMessage(), e);
            }
        }
        if (lexicon != null) {
            lists.add(lexicon);
        }
        return Vocabulary.of(lists);
    }

    /** The gzip list of postal codes next to the dict or in a bundle, or null when it is absent. */
    private static PostalCities postal(ModelSource dict) {
        ModelSource file = dict.sibling(PostalCities.FILE);
        if (file == null) {
            return null;
        }
        return PostalCities.read(wordList(file), file.toString());
    }

    private static byte[] wordList(ModelSource source) {
        try {
            return source.read();
        } catch (IOException e) {
            throw new OcrException("cannot read word list " + source + ": " + e.getMessage(), e);
        }
    }

    /**
     * Keeps only the {@code keep} columns of the recognition model's last layer: the weight
     * {@code [hidden, classes]} and the bias {@code [classes]}, and the class count of the output.
     * The softmax then runs over the kept classes only.
     */
    private static void narrowHead(Graph graph, int classes, int[] keep) {
        int changed = 0;
        for (Map.Entry<String, Tensor> entry : graph.initializers.entrySet()) {
            Tensor t = entry.getValue();
            if (t.f == null) {
                continue;
            }
            if (t.dims.length == 2 && t.dims[1] == classes) {
                int rows = t.dims[0];
                float[] out = new float[rows * keep.length];
                for (int r = 0; r < rows; r++) {
                    for (int j = 0; j < keep.length; j++) {
                        out[r * keep.length + j] = t.f[r * classes + keep[j]];
                    }
                }
                entry.setValue(Tensor.f32(new int[]{rows, keep.length}, out));
                changed++;
            } else if (t.dims.length == 1 && t.dims[0] == classes) {
                float[] out = new float[keep.length];
                for (int j = 0; j < keep.length; j++) {
                    out[j] = t.f[keep[j]];
                }
                entry.setValue(Tensor.f32(new int[]{keep.length}, out));
                changed++;
            }
        }
        if (changed == 0) {
            throw new OcrException("alphabet: no [hidden, " + classes + "] weight in the recognition model");
        }
        for (int i = 0; i < graph.outputs.size(); i++) {
            Sig sig = graph.outputs.get(i);
            if (sig.dims != null && sig.dims.length > 0 && sig.dims[sig.dims.length - 1] == classes) {
                int[] dims = sig.dims.clone();
                dims[dims.length - 1] = keep.length;
                graph.outputs.set(i, new Sig(sig.name, sig.dtype, dims));
            }
        }
    }
}
