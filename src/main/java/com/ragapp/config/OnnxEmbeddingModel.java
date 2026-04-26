package com.ragapp.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.ResourceCacheService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.DefaultResourceLoader;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Custom ONNX embedding model that uses Microsoft ONNX Runtime directly
 * (no DJL NDManager) for mean-pooled sentence embeddings.
 * Drop-in replacement for TransformersEmbeddingModel in environments where
 * the DJL native engine download is blocked (e.g. corporate SSL inspection).
 */
public class OnnxEmbeddingModel extends AbstractEmbeddingModel implements InitializingBean {

    // Use the same URLs as Spring AI's default TransformersEmbeddingModel.
    // ResourceCacheService stores them in ${java.io.tmpdir}/spring-ai-onnx-generative
    // and serves from disk on subsequent starts — no network call if already cached.
    private static final String TOKENIZER_URI =
            "https://raw.githubusercontent.com/spring-projects/spring-ai/main"
            + "/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/tokenizer.json";
    private static final String MODEL_URI =
            "https://media.githubusercontent.com/media/spring-projects/spring-ai"
            + "/refs/heads/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx";

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment environment;
    private OrtSession session;

    @Override
    public void afterPropertiesSet() throws Exception {
        // ResourceCacheService caches to ${java.io.tmpdir}/spring-ai-onnx-generative/
        // On first run it downloads; on subsequent runs it reads from disk (no SSL needed).
        ResourceCacheService cache = new ResourceCacheService();
        DefaultResourceLoader loader = new DefaultResourceLoader();

        this.tokenizer = HuggingFaceTokenizer.newInstance(
                cache.getCachedResource(loader.getResource(TOKENIZER_URI)).getInputStream(),
                Map.of());

        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            this.session = environment.createSession(
                    cache.getCachedResource(loader.getResource(MODEL_URI)).getContentAsByteArray(),
                    opts);
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> resultEmbeddings = new ArrayList<>();
        try {
            List<String> texts = request.getInstructions();
            Encoding[] encodings = tokenizer.batchEncode(texts.toArray(new String[0]));

            long[][] inputIds = new long[encodings.length][];
            long[][] attentionMask = new long[encodings.length][];
            long[][] tokenTypeIds = new long[encodings.length][];

            for (int i = 0; i < encodings.length; i++) {
                inputIds[i] = encodings[i].getIds();
                attentionMask[i] = encodings[i].getAttentionMask();
                tokenTypeIds[i] = encodings[i].getTypeIds();
            }

            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, inputIds);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
                 OnnxTensor typeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds);
                 OrtSession.Result results = session.run(Map.of(
                         "input_ids", inputIdsTensor,
                         "attention_mask", maskTensor,
                         "token_type_ids", typeIdsTensor))) {

                OnnxValue lastHiddenStateValue = results.get("last_hidden_state").get();
                float[][][] tokenEmbeddings = (float[][][]) lastHiddenStateValue.getValue();

                // Pure-Java mean pooling (replaces DJL NDManager)
                for (int b = 0; b < tokenEmbeddings.length; b++) {
                    int seqLen = tokenEmbeddings[b].length;
                    int dim = tokenEmbeddings[b][0].length;
                    float[] pooled = new float[dim];
                    float maskSum = 0f;

                    for (int s = 0; s < seqLen; s++) {
                        float m = attentionMask[b][s];
                        for (int d = 0; d < dim; d++) {
                            pooled[d] += tokenEmbeddings[b][s][d] * m;
                        }
                        maskSum += m;
                    }

                    float denom = Math.max(maskSum, 1e-9f);
                    for (int d = 0; d < dim; d++) {
                        pooled[d] /= denom;
                    }
                    resultEmbeddings.add(pooled);
                }
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX embedding inference failed", e);
        } catch (Exception e) {
            throw new RuntimeException("Embedding failed", e);
        }

        AtomicInteger idx = new AtomicInteger(0);
        return new EmbeddingResponse(resultEmbeddings.stream()
                .map(e -> new Embedding(e, idx.getAndIncrement()))
                .toList());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()))
                .getResults().stream().map(Embedding::getOutput).toList();
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        return embed(document.getText());
    }
}
