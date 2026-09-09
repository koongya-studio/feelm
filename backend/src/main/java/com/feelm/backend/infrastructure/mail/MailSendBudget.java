package com.feelm.backend.infrastructure.mail;

/**
 * 계정 메일의 전역 발송 상한이다(S15P21E106-287).
 *
 * <p>{@link #tryConsume()}은 {@link MailDispatcher}가 발송 스레드에서 발송 직전에 부른다. 요청 경로가 아닌 이유는 두 가지다. 커밋 후
 * 리스너는 <b>보낼 메일이 있을 때만</b> 불리므로 그 자리에 Redis 왕복을 넣으면 계정이 있는 경로만 느려져 S15P21E106-282가 없앤 응답 시간 차가
 * 되살아나고, 예산이 응답을 바꾸면 실제 발송을 세는 카운터가 계정 존재 여부를 알려 주는 신호가 된다(결정 D-2·D-3).
 *
 * <p>{@link #hasCapacity()}만은 요청 경로에서 쓴다. 소모하지 않고 읽기만 하며, 계정 존재 여부와 무관하게 모든 요청에 걸어 위의 시간 차가 생기지 않게
 * 한다(리뷰 B-2).
 *
 * <p>인터페이스로 둔 것은 {@link MailDispatcher}가 Redis를 직접 알지 않게 하고, 디스패처의 단위 테스트가 예산 판정을 고정할 수 있게 하기 위해서다.
 */
public interface MailSendBudget {

    /**
     * 발송 한 건의 자리를 확보한다.
     *
     * @return 보내도 되면 {@code true}, 어느 한 창이라도 상한을 넘었으면 {@code false}
     */
    boolean tryConsume();

    /**
     * {@link #tryConsume()}으로 확보한 자리를 되돌린다. 발송이 실패해 SMTP 쿼터를 쓰지 않았을 때 부른다(리뷰 B-4).
     *
     * <p>확보에 성공한 발송에 대해서만 부른다. 확보하지 못한 요청까지 되돌리면 카운터가 실제 발송보다 적어진다.
     */
    void release();

    /**
     * 지금 보낼 자리가 있는지 소모하지 않고 본다.
     *
     * <p>요청 경로가 이 값을 보고 토큰 회전처럼 되돌릴 수 없는 부작용을 건너뛴다. 확인과 실제 발송 사이에 예산이 마를 수 있으므로 보장이 아니라 <b>지금
     * 상태</b>다.
     *
     * @return 자리가 있으면 {@code true}, 상한에 닿았거나 값을 읽지 못하면 {@code false}
     */
    boolean hasCapacity();
}
