//package com.example.onlyone.domain.payment.dto.response;
//
//import lombok.AllArgsConstructor;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//
//@Getter
//@NoArgsConstructor
//@AllArgsConstructor
//public class CancelTossPayResponse {
//    private String paymentKey;
//    private String walletTransactionType;
//    private String orderId;
//    private String orderName;
//    private String method;
//    private Long totalAmount;
//    private Long balanceAmount;
//    private String status;
//    private String requestedAt;
//    private String approvedAt;
//    private String lastTransactionKey;
//    private Long vat;
//    private boolean isPartialCancelable;
//    private CancelDetail[] cancels;
//    private Receipt receipt;
//
//    @Getter
//    @NoArgsConstructor
//    @AllArgsConstructor
//    public static class CancelDetail {
//        private Long cancelAmount;
//        private String cancelReason;
//        private Long refundableAmount;
//        private String canceledAt;
//        private String transactionKey;
//    }
//
//    @Getter
//    @NoArgsConstructor
//    @AllArgsConstructor
//    public static class Receipt {
//        private String url;
//    }
//}
