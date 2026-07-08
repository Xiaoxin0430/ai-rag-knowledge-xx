package cn.xx.xx.dev.tech.trigger.http;

import cn.xx.xx.dev.tech.api.IRAGService;
import cn.xx.xx.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
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
import org.springframework.core.io.PathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

    @RequestMapping(value = "chat", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> chat(@RequestParam(value = "model", defaultValue = DEFAULT_MODEL) String model,
                                   @RequestParam("message") String message,
                                   @RequestParam(value = "ragTag", required = false) String ragTag) {
        if (StringUtils.isBlank(ragTag)) {
            return ollamaChatClient.stream(new Prompt(
                    message,
                    OllamaOptions.create().withModel(StringUtils.defaultIfBlank(model, DEFAULT_MODEL))
            ));
        }

        String systemPrompt = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documents}
                """;

        SearchRequest request = SearchRequest.query(message)
                .withTopK(5)
                .withFilterExpression(buildKnowledgeFilter(ragTag));

        List<Document> documents = pgVectorStore.similaritySearch(request);
        String documentCollectors = documents.stream().map(Document::toString).collect(Collectors.joining());
        Message ragMessage = new SystemPromptTemplate(systemPrompt).createMessage(Map.of("documents", documentCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(ragMessage);
        messages.add(new UserMessage(message));

        return ollamaChatClient.stream(new Prompt(
                messages,
                OllamaOptions.create().withModel(StringUtils.defaultIfBlank(model, DEFAULT_MODEL))
        ));
    }

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
    public Response<String> uploadFile(@RequestParam("ragTag") String ragTag, @RequestParam("file") List<MultipartFile> files) {
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



    //    http://localhost:8090/api/v1/analyze_git_repository
    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam String repoUrl, @RequestParam String username, @RequestParam String password) throws Exception {

        String localPath = "./cloned-repo";
        String repoProjectName = extractProjectName(repoUrl);
        log.info("克隆路径：" + new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username,password))
                .call();

        Files.walkFileTree(Paths.get("./cloned-repo"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                log.info("文件路径:{}",file.toAbsolutePath());

                try {
                    PathResource resource = new PathResource(file);
                    TikaDocumentReader reader = new TikaDocumentReader(resource);

                    List<Document> documents = reader.get();

                    if (documents == null || documents.isEmpty()) {
                        log.info("跳过空文档:{}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

                    documentSplitterList.forEach(doc ->
                            doc.getMetadata().put("knowledge", repoProjectName)
                    );

                    pgVectorStore.accept(documentSplitterList);

                } catch (Exception e) {
                    log.warn("文件解析失败，已跳过: {}", file.toAbsolutePath(), e);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                log.info("Failed to access file: {} - {}", file.toString(), exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        git.close();

        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) {
            elements.add(repoProjectName);
        }

        log.info("遍历解析路径，上传完成:{}", repoUrl);

        return Response.<String>builder().code("0000").info("调用成功").build();

    }

    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git","");
    }

    private String buildKnowledgeFilter(String ragTag) {
        return "knowledge == '" + ragTag.replace("'", "\\'") + "'";
    }

}
