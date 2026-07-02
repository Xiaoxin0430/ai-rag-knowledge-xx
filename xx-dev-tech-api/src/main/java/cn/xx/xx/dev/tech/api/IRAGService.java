package cn.xx.xx.dev.tech.api;

import cn.xx.xx.dev.tech.api.response.Response;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

public interface IRAGService {

    Response<List<String>> queryRagTagList();

    Response<String> uploadFile(String ragTag, List<MultipartFile> files);

    Flux<ChatResponse> RAGChat(String ragTag, String model, String message);

}
