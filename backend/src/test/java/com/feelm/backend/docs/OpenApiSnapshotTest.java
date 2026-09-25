package com.feelm.backend.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectMapper;

/**
 * springdoc이 만드는 OpenAPI 문서와 프론트가 타입을 만들 때 쓰는 스냅샷이 같은지 본다 (S15P21E106-777).
 *
 * <p>프론트의 응답 타입은 모두 손으로 쓴 것이라 백엔드 DTO가 바뀌어도 따라가지 않았다. 프론트는 {@code
 * frontend/src/generated/openapi.json} 하나를 입력으로 타입을 만들고, 그 스냅샷이 실제 문서와 같은지는 이 테스트가 지킨다. <b>백엔드 계약을
 * 바꿨는데 스냅샷을 갱신하지 않으면 여기서 깨진다.</b>
 *
 * <p>스냅샷을 갱신하는 방법은 다음 한 줄이다. 실행하면 파일을 다시 쓰고 같은 실행에서 비교까지 한다.
 *
 * <pre>FEELM_OPENAPI_SNAPSHOT_WRITE=1 ./gradlew test --tests '*OpenApiSnapshotTest'</pre>
 *
 * <p>시스템 속성이 아니라 환경 변수인 것은 Gradle {@code Test} task가 {@code -D}를 테스트 JVM으로 넘기지 않기 때문이다. 넘기게 하려면
 * {@code build.gradle}을 고쳐야 하고, 그것은 backend/AGENTS.md 3장의 게이트 트리거다 — 테스트 코드만 바꾸는 이 변경에 맞지 않는다.
 *
 * <p><b>격리된 MR Job에는 볼 대상이 없다.</b> {@code backend-mr-test}는 {@code backend/}만 담은 스냅샷을 받아 {@code
 * frontend/} 자체가 없다(infra/scripts/FRONTEND-MR-ISOLATION.md와 같은 경계). 그 경우에만 아무것도 하지 않고 지나가고, 전체 저장소를
 * 보는 {@code backend-test}(main)와 로컬 실행에서 검사한다. 프론트 쪽 검사(스냅샷 → 생성 타입)는 {@code vite build}가 한다.
 *
 * <p>JUnit의 가정({@code assumeTrue})으로 건너뛰지 <b>않는다.</b> 백엔드 MR Job의 판정 정책이 건너뛴 테스트를 하나라도 보면 실패로
 * 본다({@code infra/scripts/ci-job-safety.py} — {@code summary['skipped']}). 대신 {@code frontend/}
 * 디렉터리 자체가 없을 때만 조용히 지나가고, 디렉터리는 있는데 스냅샷 파일이 없으면 <b>실패시킨다</b> — 그것은 격리가 아니라 지워진 것이다.
 *
 * <p>비교 전에 <b>양쪽을 같은 방식으로 정규화한다</b> — 객체 키를 정렬하고 같은 방식으로 들여쓴다. springdoc은 문서를 reflection으로 모으므로 키
 * 순서가 실행마다 달라질 수 있고, 파일이 다른 도구로 다시 포맷되어도 내용이 같으면 통과해야 한다.
 *
 * <p>줄바꿈은 플랫폼과 무관하게 {@code \n}으로 쓴다. 저장소가 {@code .gitattributes}로 LF를 강제하므로, Windows에서 갱신할 때 CRLF로
 * 쓰면 파일이 매번 수정 상태로 남는다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("OpenAPI 스냅샷 — 프론트 타입 생성 입력")
class OpenApiSnapshotTest extends SharedPostgresContractTest {

    /** 프론트 디렉터리. 격리된 MR Job에는 아예 없다. 테스트는 {@code backend/}에서 실행되므로 한 단계 위로 올라간다. */
    private static final Path FRONTEND = Path.of("..", "frontend");

    /** 프론트가 타입을 만들 때 읽는 스냅샷 */
    private static final Path SNAPSHOT =
            FRONTEND.resolve(Path.of("src", "generated", "openapi.json"));

    /** 값이 있으면 비교 전에 스냅샷을 실제 문서로 다시 쓴다. 갱신은 사람이 명시할 때만 일어난다. */
    private static final String WRITE_VARIABLE = "FEELM_OPENAPI_SNAPSHOT_WRITE";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    /** OS와 무관하게 같은 바이트가 나오도록 두 칸 들여쓰기와 {@code \n}을 고정한다. */
    private static DefaultPrettyPrinter printer() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");

        return new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter);
    }

    @Test
    @DisplayName("프론트 스냅샷이 springdoc 문서와 같다")
    void snapshotMatchesGeneratedDocument() throws Exception {
        String document =
                normalized(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8));

        if (!System.getenv().getOrDefault(WRITE_VARIABLE, "").isBlank()) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, document + "\n", StandardCharsets.UTF_8);
        }

        if (!Files.isDirectory(FRONTEND)) {
            // 프론트를 담지 않는 격리 Job이다. 볼 대상이 없다
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .as("frontend/src/generated/openapi.json 이 없다. 프론트 타입 생성의 입력이므로 지우면 안 된다.")
                .isTrue();

        assertThat(normalized(Files.readString(SNAPSHOT, StandardCharsets.UTF_8)))
                .as(
                        "백엔드 계약이 바뀌었다. FEELM_OPENAPI_SNAPSHOT_WRITE=1 로 스냅샷을 다시 쓰고"
                                + " 프론트 타입(npm run api:generate)까지 갱신해야 한다.")
                .isEqualTo(document);
    }

    /** 키를 정렬하고 같은 방식으로 들여쓴 문자열. 순서와 공백 차이로 깨지지 않게 한다. */
    private String normalized(String json) throws IOException {
        Object tree = objectMapper.readValue(json, Object.class);

        return objectMapper.writer().with(printer()).writeValueAsString(sorted(tree));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            for (Map.Entry<?, ?> entry : new LinkedHashMap<>(map).entrySet()) {
                ordered.put(String.valueOf(entry.getKey()), sorted(entry.getValue()));
            }
            return ordered;
        }
        if (value instanceof List<?> list) {
            // 배열 순서는 계약의 일부다(파라미터 · enum 값). 정렬하지 않는다.
            List<Object> ordered = new ArrayList<>(list.size());
            for (Object element : list) {
                ordered.add(sorted(element));
            }
            return ordered;
        }
        return value;
    }
}
