package cn.xx.xx.dev.tech.api;

import cn.xx.xx.dev.tech.api.response.Response;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.FileVisitResult;
import java.util.List;

public interface IRAGService {

    Response<List<String>> queryRagTagList();

    Response<String> uploadFile(String ragTag, List<MultipartFile> files);

    Response<String> analyzeGitRepository(String repoUrl, String username, String passward) throws Exception;
}
