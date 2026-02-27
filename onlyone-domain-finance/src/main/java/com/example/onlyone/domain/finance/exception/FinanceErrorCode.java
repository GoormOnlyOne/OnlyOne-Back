package com.example.onlyone.domain.finance.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FinanceErrorCode implements ErrorCode {

    // Settlement
    MEMBER_CANNOT_CREATE_SETTLEMENT(403, "SETTLEMENT_403_1", "리더만 정산 요청을 할 수 있습니다."),
    SETTLEMENT_NOT_FOUND(404, "SETTLEMENT_404_1", "정산을 찾을 수 없습니다."),
    USER_SETTLEMENT_NOT_FOUND(404, "SETTLEMENT_404_2", "정산 참여자를 찾을 수 없습니다."),
    ALREADY_SETTLED_USER(409, "SETTLEMENT_409_1", "이미 해당 정기 모임에 대해 정산한 유저입니다."),
    ALREADY_COMPLETED_SETTLEMENT(409, "SETTLEMENT_409_2", "이미 종료된 정산입니다."),
    SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_1", "정기 모임을 찾을 수 없습니다."),
    ALREADY_SETTLING_SCHEDULE(409, "SCHEDULE_409_7", "이미 정산 진행 중인 정기 모임입니다."),
    SETTLEMENT_PROCESS_FAILED(500, "SETTLEMENT_500_1", "정산 처리 중 오류가 발생했습니다. 다시 시도해 주세요."),

    // Wallet
    INVALID_FILTER(400, "WALLET_400_1", "유효하지 않은 필터입니다."),
    WALLET_NOT_FOUND(404, "WALLET_404_1", "사용자의 지갑을 찾을 수 없습니다."),
    WALLET_BALANCE_NOT_ENOUGH(409, "WALLET_409_1", "사용자의 잔액이 부족합니다."),
    WALLET_HOLD_STATE_CONFLICT(409, "WALLET_409_2", "사용자의 예약금이 부족합니다. 포인트를 충전해 주세요."),
    WALLET_HOLD_CAPTURE_FAILED(409, "WALLET_409_3", "사용자의 예약금 차감에 실패했습니다. 다시 시도해 주세요."),
    WALLET_CREDIT_APPLY_FAILED(409, "WALLET_409_4", "리더의 정산금 처리에 실패했습니다. 다시 시도해 주세요."),
    WALLET_OPERATION_IN_PROGRESS(409, "WALLET_409_5", "사용자의 다른 거래가 처리 중입니다. 잠시 후 다시 시도해 주세요."),

    // Payment
    PAYMENT_IN_PROGRESS(202, "PAYMENT_202_1", "결제 처리 중입니다. 잠시 후 다시 조회해 주세요."),
    INVALID_PAYMENT_INFO(400, "PAYMENT_400_1", "결제 정보가 유효하지 않습니다."),
    ALREADY_COMPLETED_PAYMENT(409, "PAYMENT_409_1", "이미 결제가 완료되었습니다."),
    TOSS_PAYMENT_FAILED(502, "PAYMENT_502_1", "토스페이먼츠 결제 승인에 실패했습니다."),
    TOSS_CANCEL_FAILED(502, "PAYMENT_502_2", "토스페이먼츠 결제 취소에 실패했습니다. 수동 확인이 필요합니다."),

    // Outbox
    INVALID_TOPIC(400, "OUTBOX_400_1", "유효하지 않은 토픽입니다."),
    INVALID_EVENT_PAYLOAD(422, "OUTBOX_422_1", "잘못된 이벤트 페이로드입니다.");

    private final int status;
    private final String code;
    private final String message;
}
