package cn.xx.xx.dev.tech.trigger.http;

import cn.xx.xx.dev.tech.api.IRAGService;
import cn.xx.xx.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {

    private static final String DEFAULT_MODEL = "deepseek-r1:1.5b";

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Override
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("Upload knowledge base start. ragTag={}", ragTag);

        if (StringUtils.isBlank(ragTag)) {
            return Response.<String>builder().code("0001").info("知识库名称不能为空").build();
        }
        if (files == null || files.isEmpty()) {
            return Response.<String>builder().code("0001").info("上传文件不能为空").build();
        }

        String knowledge = ragTag.trim();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = documentReader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            documents.forEach(doc -> doc.getMetadata().put("knowledge", knowledge));
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", knowledge));

            pgVectorStore.accept(documentSplitterList);
        }

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(knowledge)) {
            elements.add(knowledge);
        }

        log.info("Upload knowledge base complete. ragTag={}", knowledge);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    @RequestMapping(value = "chat", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ChatResponse> RAGChat(@RequestParam(required = false) String ragTag,
                                      @RequestParam(defaultValue = DEFAULT_MODEL) String model,
                                      @RequestParam String message) {

        String modelName = StringUtils.defaultIfBlank(model, DEFAULT_MODEL);

        if(StringUtils.isBlank(ragTag)) {
            return ollamaChatClient.stream(new Prompt(
                    message,
                    OllamaOptions.create().withModel(modelName)
            ));
        }

        String systemPrompt = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documents}
                """;

        String knowledge = ragTag.trim();
        String userMessage = message.trim();

        SearchRequest request = SearchRequest.query(userMessage)
                .withTopK(4)
                .withFilterExpression("knowledge == '" + knowledge.replace("'", "\\'") + "'");

        List<Document> documents = pgVectorStore.similaritySearch(request);
        String documentsCollectors = documents.stream().map(Document::toString).collect(Collectors.joining());

        Message ragMessage = new SystemPromptTemplate(systemPrompt).createMessage(Map.of("documents", documentsCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(ragMessage);
        messages.add(new UserMessage(userMessage));

        return ollamaChatClient.stream(new Prompt(
                messages,
                OllamaOptions.create().withModel(modelName)
        ));
    }

}
