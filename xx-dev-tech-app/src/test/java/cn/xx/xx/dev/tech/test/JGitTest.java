package cn.xx.xx.dev.tech.test;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.PathResource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class JGitTest {

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore vectorStore;
    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void test() throws Exception {
        String repoURL = System.getenv("GIT_REPO_URL");
        String username = System.getenv("GIT_USERNAME");
        String password = System.getenv("GIT_PASSWORD");

        if (repoURL == null || username == null || password == null) {
            throw new IllegalStateException("Please set GIT_REPO_URL, GIT_USERNAME and GIT_PASSWORD before running this test.");
        }
        String localPath = "./cloned-repo";
        log.info("克隆路径：" + new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoURL)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username,password))
                .call();

        git.close();
    }

    @Test
    public void test_file() throws IOException {
        Files.walkFileTree(Paths.get("./cloned-repo"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String dirName = dir.getFileName().toString();

                if (dirName.equals(".git") || dirName.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

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
                            doc.getMetadata().put("knowledge", "group-buy-market-liergou")
                    );

                    pgVectorStore.accept(documentSplitterList);

                } catch (Exception e) {
                    log.warn("文件解析失败，已跳过: {}", file.toAbsolutePath(), e);
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }
}
