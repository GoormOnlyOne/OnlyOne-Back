package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.QClub;
import com.example.onlyone.domain.club.entity.QUserClub;
import com.example.onlyone.domain.interest.entity.QInterest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClubRepositoryImpl implements ClubRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QClub club = QClub.club;
    private static final QUserClub userClub = QUserClub.userClub;
    private static final QInterest interest = QInterest.interest;

    @Override
    public List<Object[]> findClubsByTeammates(Long userId, Pageable pageable) {
        // 3-step 접근: 비싼 JOIN materialization을 3개의 작은 인덱스 스캔으로 분리
        // Step 1: 사용자의 최근 10개 모임 ID (인덱스 스캔, ~1ms)
        QUserClub myClubs = new QUserClub("myClubs");
        List<Long> recentClubIds = queryFactory
            .select(myClubs.club.clubId)
            .from(myClubs)
            .where(myClubs.user.userId.eq(userId))
            .orderBy(myClubs.createdAt.desc())
            .limit(10)
            .fetch();

        if (recentClubIds.isEmpty()) {
            return List.of();
        }

        // Step 2: 해당 모임의 팀원 ID 목록 (covering index 스캔, ~5ms)
        // 100명으로 제한하여 candidate clubs 폭발 방지 (100 × 15 = ~1,500 clubs)
        QUserClub teammates = new QUserClub("teammates");
        List<Long> teammateIds = queryFactory
            .selectDistinct(teammates.user.userId)
            .from(teammates)
            .where(
                teammates.club.clubId.in(recentClubIds)
                    .and(teammates.user.userId.ne(userId))
            )
            .limit(100)
            .fetch();

        if (teammateIds.isEmpty()) {
            return List.of();
        }

        // Step 3: 팀원들의 클럽 중 내가 참여하지 않은 클럽 조회 (~10ms)
        // 사용자의 클럽 ID를 먼저 조회하여 NOT IN을 Java에서 처리
        QUserClub excl = new QUserClub("excl");
        List<Long> myClubIds = queryFactory
            .select(excl.club.clubId)
            .from(excl)
            .where(excl.user.userId.eq(userId))
            .fetch();

        QUserClub theirClubs = new QUserClub("theirClubs");
        List<Long> candidateClubIds = queryFactory
            .selectDistinct(theirClubs.club.clubId)
            .from(theirClubs)
            .where(theirClubs.user.userId.in(teammateIds))
            .fetch();

        // Java에서 사용자의 클럽 제외 (DB 부하 감소, HashSet O(1) 룩업)
        Set<Long> myClubIdSet = new HashSet<>(myClubIds);
        List<Long> filteredClubIds = candidateClubIds.stream()
            .filter(id -> !myClubIdSet.contains(id))
            .toList();

        if (filteredClubIds.isEmpty()) {
            return List.of();
        }

        // 최종: 단순 WHERE IN으로 클럽 조회 (PK 룩업, ~5ms)
        return queryFactory
            .select(club, club.memberCount)
            .from(club)
            .where(club.clubId.in(filteredClubIds))
            .orderBy(club.memberCount.desc(), club.createdAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(tuple -> new Object[] {
                tuple.get(club),
                tuple.get(club.memberCount)
            })
            .toList();
    }

    @Override
    public List<Object[]> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");
        BooleanBuilder whereCondition = new BooleanBuilder();

        // 안전한 interestIds 처리
        if (interestIds != null && !interestIds.isEmpty()) {
            whereCondition.and(club.interest.interestId.in(interestIds));
        }

        // 안전한 city 처리
        if (city != null && !city.trim().isEmpty()) {
            whereCondition.and(club.city.eq(city));
        }

        // 안전한 district 처리
        if (district != null && !district.trim().isEmpty()) {
            whereCondition.and(club.district.eq(district));
        }

        // 안전한 userId 처리
        if (userId != null) {
            whereCondition.and(
                JPAExpressions.selectOne()
                        .from(excludeUserClub)
                        .where(
                                excludeUserClub.club.clubId.eq(club.clubId)
                                        .and(excludeUserClub.user.userId.eq(userId))
                        )
                        .notExists()
            );
        }

        return queryFactory
                .select(club)
                .from(club)
                .where(whereCondition)
                .orderBy(club.memberCount.desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(club -> new Object[] {
                        club,
                        club.getMemberCount()
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");

        return queryFactory
                .select(club)
                .from(club)
                .where(
                        club.interest.interestId.in(interestIds)
                                .and(
                                        JPAExpressions.selectOne()
                                                .from(excludeUserClub)
                                                .where(
                                                        excludeUserClub.club.clubId.eq(club.clubId)
                                                                .and(excludeUserClub.user.userId.eq(userId))
                                                )
                                                .notExists()
                                )
                )
                .orderBy(club.memberCount.desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(club -> new Object[] {
                        club,
                        club.getMemberCount()
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByInterest(Long interestId, Pageable pageable) {
        BooleanBuilder whereCondition = new BooleanBuilder();

        // 안전한 interestId 처리
        if (interestId != null) {
            whereCondition.and(club.interest.interestId.eq(interestId));
        }

        return queryFactory
                .select(club)
                .from(club)
                .where(whereCondition)
                .orderBy(club.memberCount.desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(club -> new Object[] {
                        club,
                        club.getMemberCount()
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByLocation(String city, String district, Pageable pageable) {
        BooleanBuilder whereCondition = new BooleanBuilder();

        // 안전한 city 처리
        if (city != null && !city.trim().isEmpty()) {
            whereCondition.and(club.city.eq(city));
        }

        // 안전한 district 처리
        if (district != null && !district.trim().isEmpty()) {
            whereCondition.and(club.district.eq(district));
        }

        return queryFactory
                .select(club)
                .from(club)
                .where(whereCondition)
                .orderBy(club.memberCount.desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(club -> new Object[] {
                        club,
                        club.getMemberCount()
                })
                .toList();
    }
}
