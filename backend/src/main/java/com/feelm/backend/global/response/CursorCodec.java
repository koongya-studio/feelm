package com.feelm.backend.global.response;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

/**
 * 커서 페이지네이션의 불투명 커서를 인코딩하고 해독한다.
 *
 * <p>공통 응답 규약이 커서를 서버가 발급한 불투명 문자열로 정의하므로 인코딩 형식과 방어 규칙은 여기서만 정하고 각 도메인은 커서에 담을 값만 정한다. 커서 타입 자체는
 * 공유하지 않는다. 정렬 키가 도메인마다 다르고 도메인 내부 타입을 도메인 밖으로 노출하지 않기 위해서다(컨벤션 11장).
 *
 * <p>커서는 클라이언트가 고쳐 보낼 수 있는 입력이다. 해독에 실패하는 모든 경우를 {@link ErrorCode#VALIDATION_FAILED}로 모아, 위조된 값이
 * 500 응답이 되지 않게 한다.
 *
 * <p>진입점은 둘이다. 시각을 담는 커서는 {@link #decodeInstant}를 쓰고, 그 밖의 커서는 {@link #decode}에 도메인 팩토리를 넘긴다.
 *
 * <p>형식은 {@code <앞부분><구분자><식별자>}를 base64url로 인코딩한 것이다. 앞부분에 구분자가 들어가도 경계가 흔들리지 않도록 마지막 구분자를 기준으로
 * 나눈다. 뒤쪽은 항상 숫자인 식별자다.
 */
public final class CursorCodec {

    /**
     * 해독 전 문자열 길이 상한.
     *
     * <p>커서에 담는 앞부분은 시각 문자열이거나 컬럼 길이가 제한된 짧은 값이라, 구분자와 {@code long} 최대 자릿수를 더해도 base64로 이 길이를 넘지
     * 않는다.
     *
     * <p>이 상한은 응답 코드를 가르는 검사가 아니라 <b>해독 이전의 자원 상한</b>이다. 상한을 넘는 값은 어차피 앞부분 해석이나 식별자 파싱에서 걸리므로 상한을
     * 지워도 응답은 400 그대로다. 상한이 하는 일은 거대한 {@code ?cursor=} 값을 base64로 해독해 메모리에 올리기 전에 끊는 것이다. 도메인 검증과
     * 중복돼 보여도 지우지 않는다.
     */
    private static final int MAX_ENCODED_LENGTH = 128;

    private static final char SEPARATOR = ':';

    /**
     * 커서에 담긴 생성 시각의 하한.
     *
     * <p>{@code timestamptz}는 훨씬 이른 시각도 저장하므로 이 하한의 근거는 저장 한계가 아니라 도메인 전제다. 커서에는 이미 저장된 생성 시각만 들어오고
     * 그 값은 서비스 개시 이후다. 개봉일이나 생년월일처럼 epoch 이전이 정상인 정렬 키를 커서에 담아야 하면 이 상수를 그대로 쓰지 말고 범위를 다시 정해야 한다.
     * 그러지 않으면 서버가 스스로 발급한 커서를 거절한다.
     */
    private static final Instant MIN_INSTANT = Instant.EPOCH;

    /**
     * 커서에 담긴 시각의 상한.
     *
     * <p>{@code Instant}는 연도 10억까지 파싱하지만 그 값을 질의에 바인딩하면 드라이버나 DB가 거절해 클라이언트 입력이 500 응답이 된다. 저장 가능한
     * 최대치보다 훨씬 낮게 잡은 것은 하한과 같은 이유이며, 생성 시각 커서에는 미래 값이 들어올 일이 없기 때문이다.
     */
    private static final Instant MAX_INSTANT = Instant.parse("2100-01-01T00:00:00Z");

    private CursorCodec() {}

    /**
     * 해독한 앞부분과 식별자로 도메인 커서를 만든다.
     *
     * <p>도메인 고유 규칙은 이 안에서 검사하고 어긋나면 {@link IllegalArgumentException}을 던진다. {@link #decode}가 그것을
     * {@link ErrorCode#VALIDATION_FAILED}로 바꾼다.
     */
    @FunctionalInterface
    public interface CursorFactory<T> {
        T create(String head, long id);
    }

    /**
     * 해독한 시각과 식별자로 도메인 커서를 만든다.
     *
     * <p>{@link #decodeInstant}가 앞부분을 저장 가능한 시각으로 해석한 뒤 호출하므로, 시각 커서는 범위 검증을 건너뛸 수 없다.
     */
    @FunctionalInterface
    public interface InstantCursorFactory<T> {
        T create(Instant head, long id);
    }

    /** 시각과 식별자를 담은 커서를 만든다. */
    public static String encode(Instant head, long id) {
        return encode(head.toString(), id);
    }

    /** 문자열과 식별자를 담은 커서를 만든다. */
    public static String encode(String head, long id) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((head + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서를 해독해 도메인 커서로 만든다. 값이 없으면 첫 페이지를 뜻하는 {@code null}을 돌려준다.
     *
     * <p>길이 상한, base64url 해독, 구분자, 식별자 하한을 공통으로 확인한 뒤 {@code factory}에 넘긴다.
     *
     * @throws BusinessException 형식이 깨졌거나 도메인 규칙에 어긋나면 {@link ErrorCode#VALIDATION_FAILED}
     */
    public static <T> T decode(String encoded, CursorFactory<T> factory) {
        if (encoded == null) {
            return null;
        }
        try {
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("cursor too long");
            }
            String value =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf(SEPARATOR);
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            long id = Long.parseLong(value.substring(separator + 1));
            if (id < 1) {
                throw new IllegalArgumentException("invalid cursor id");
            }
            return factory.create(value.substring(0, separator), id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 시각을 담은 커서를 해독한다. 값이 없으면 첫 페이지를 뜻하는 {@code null}을 돌려준다.
     *
     * <p>{@link #decode}의 공통 확인에 더해 앞부분을 시각으로 해석하고 허용 범위 안인지 본다. 시각 커서는 이 진입점을 쓴다. {@link #decode}에
     * 직접 {@code Instant.parse}를 쓰는 팩토리를 넘기면 범위를 벗어난 시각이 질의로 내려가 500이 되므로, 그 실수가 생기지 않도록 범위 검증을 이
     * 메서드 안에 가둔다.
     *
     * @throws BusinessException 형식이 깨졌거나 시각이 허용 범위를 벗어나면 {@link ErrorCode#VALIDATION_FAILED}
     */
    public static <T> T decodeInstant(String encoded, InstantCursorFactory<T> factory) {
        return decode(encoded, (head, id) -> factory.create(storableInstant(head), id));
    }

    /**
     * 커서 앞부분을 허용 범위 안의 시각으로 해석한다.
     *
     * @throws DateTimeException 시각 형식이 아니면
     * @throws IllegalArgumentException 허용 범위를 벗어나면
     */
    private static Instant storableInstant(String head) {
        Instant instant = Instant.parse(head);
        if (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
            throw new IllegalArgumentException("cursor time out of range");
        }
        return instant;
    }
}
