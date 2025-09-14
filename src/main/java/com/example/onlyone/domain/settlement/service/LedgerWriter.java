package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.TransferRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.wallet.entity.Transfer;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.domain.wallet.entity.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Component
@RequiredArgsConstructor
// LedgerWriter: user-settlement.result.v1 토픽만 구독
public class LedgerWriter {

    private final ObjectMapper objectMapper;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final UserSettlementRepository userSettlementRepository;

    /*
    public class ConsumerRecord<K, V> {
        private final String topic;     // 토픽명
        private final int partition;    // 파티션 번호
        private final long offset;      // 해당 파티션 내 오프셋
        private final K key;            // Kafka 메시지 Key
        private final V value;          // Kafka 메시지 Value (JSON String)
        private final long timestamp;   // 메시지 발생/전송 시간
        ...
    }
     */

    @Transactional
    public void writeBatch(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            log.info("✅ LedgerWriter: No records received");
            return;
        }

        log.info("✅ LedgerWriter: Received {} records", records.size());

        List<WalletTransaction> walletTransactionList = new ArrayList<>();
        List<Transfer> transferList = new ArrayList<>();

        // 1) 파싱 및 candidate operationId 수집
        List<JsonNode> events = records.stream()
                .map(r -> parse(r.value()))
                .toList();

        log.info("✅ LedgerWriter: Parsed {} events", events.size());

        Set<String> candidateoperationIds = new HashSet<>();
        for (JsonNode root : events) {
            String operationId = root.path("operationId").asText();
            candidateoperationIds.add(operationId + ":OUT");
            candidateoperationIds.add(operationId + ":IN");
        }

        log.debug("✅ LedgerWriter: Candidate operationIds = {}", candidateoperationIds);


        // 2) 이미 처리된 operationId 조회
        Set<String> existing = new HashSet<>(walletTransactionRepository.findExistingOperationIds(candidateoperationIds));

        // 3) WalletTransaction / Transfer 생성
        Map<String, WalletTransaction> walletTransactionHashMap = new HashMap<>();
        for (JsonNode root : events) {
            String type = root.path("type").asText("SUCCESS");
            String operationId = root.path("operationId").asText();

            long userSettlementId = root.path("userSettlementId").asLong();
            long memberWalletId   = root.path("memberWalletId").asLong();
            long leaderWalletId   = root.path("leaderWalletId").asLong();
            long amount           = root.path("amount").asLong();

            Wallet memberWallet = walletRepository.getReferenceById(memberWalletId);
            Wallet leaderWallet = walletRepository.getReferenceById(leaderWalletId);
            UserSettlement us   = userSettlementRepository.getReferenceById(userSettlementId);

            WalletTransactionStatus status =
                    type.equals("SUCCESS") ? WalletTransactionStatus.COMPLETED : WalletTransactionStatus.FAILED;

            // OUTGOING
            String outId = operationId + ":OUT";
            if (!existing.contains(outId)) {
                WalletTransaction outTransaction = WalletTransaction.builder()
                        .operationId(outId)
                        .type(Type.OUTGOING)
                        .wallet(memberWallet)
                        .targetWallet(leaderWallet)
                        .amount(amount)
                        .balance(memberWallet.getPostedBalance())
                        .walletTransactionStatus(status)
                        .build();
                walletTransactionList.add(outTransaction);
                walletTransactionHashMap.put(outId, outTransaction);

                Transfer outTransfer = Transfer.builder()
                        .userSettlement(us)
                        .walletTransaction(outTransaction)
                        .build();
                transferList.add(outTransfer);
                outTransaction.updateTransfer(outTransfer);

                transferList.add(Transfer.builder()
                        .userSettlement(us)
                        .walletTransaction(outTransaction)
                        .build());
            }
            // INCOMING
            String inId = operationId + ":IN";
            if (!existing.contains(inId)) {
                WalletTransaction inTransaction = WalletTransaction.builder()
                        .operationId(inId)
                        .type(Type.INCOMING)
                        .wallet(leaderWallet)
                        .targetWallet(memberWallet)
                        .amount(amount)
                        .balance(leaderWallet.getPostedBalance())
                        .walletTransactionStatus(status)
                        .build();
                walletTransactionList.add(inTransaction);
                walletTransactionHashMap.put(inId, inTransaction);

                Transfer inTransfer = Transfer.builder()
                        .userSettlement(us)
                        .walletTransaction(inTransaction)
                        .build();
                transferList.add(inTransfer);
                inTransaction.updateTransfer(inTransfer);
            }
        }

        // 4) WalletTransaction 저장 (배치 + 충돌 시 개별 재시도)
        if (!walletTransactionList.isEmpty()) {
            try {
                walletTransactionRepository.saveAll(walletTransactionList);
                walletTransactionRepository.flush();
            } catch (DataIntegrityViolationException dup) {
                insertIndividuallyIgnoringDuplicate(walletTransactionList);
            }
        }

        // 5) Transfer 저장 (성능 개선: 배치 크기 제한)
        if (!transferList.isEmpty()) {
            try {
                // 배치 크기를 1000으로 제한하여 메모리 사용량 최적화
                int batchSize = 1000;
                for (int i = 0; i < transferList.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, transferList.size());
                    List<Transfer> batch = transferList.subList(i, endIndex);
                    transferRepository.saveAll(batch);
                }
                transferRepository.flush();
            } catch (DataIntegrityViolationException dup) {
                // 필요시 개별 재시도 가능
                log.warn("Transfer batch insert failed, some records may be duplicates", dup);
            }
        }
    }

    private JsonNode parse(String s) {
        try { return objectMapper.readTree(s); }
        catch (Exception e) { throw new RuntimeException("Invalid JSON: " + s, e); }
    }

    private void insertIndividuallyIgnoringDuplicate(List<WalletTransaction> walletTransactionList) {
        Set<String> existing = new HashSet<>(
                walletTransactionRepository.findExistingOperationIds(
                        walletTransactionList.stream().map(WalletTransaction::getOperationId).collect(Collectors.toSet())
                )
        );
        for (WalletTransaction walletTransaction : walletTransactionList) {
            if (existing.contains(walletTransaction.getOperationId())) continue;
            try {
                walletTransactionRepository.saveAndFlush(walletTransaction);
            } catch (DataIntegrityViolationException ignored) {
                // 동시경합으로 중복키면 그냥 스킵
            }
        }
    }
}
