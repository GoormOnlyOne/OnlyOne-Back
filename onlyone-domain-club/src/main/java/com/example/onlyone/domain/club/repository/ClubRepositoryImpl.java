package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.QClub;
import com.example.onlyone.domain.club.entity.QUserClub;
import com.querydsl.core.BooleanBuilder;
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

    @Override
    public List<ClubWithMemberCount> findClubsByTeammates(Long userId, Pageable pageable) {
        // Step 1: 사용자의 최근 10개 모임 ID
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

        // Step 2: 해당 모임의 팀원 ID 목록
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

        // Step 3: 팀원들의 클럽 중 내가 참여하지 않은 클럽 조회
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

        Set<Long> myClubIdSet = new HashSet<>(myClubIds);
        List<Long> filteredClubIds = candidateClubIds.stream()
            .filter(id -> !myClubIdSet.contains(id))
            .toList();

        if (filteredClubIds.isEmpty()) {
            return List.of();
        }

        return queryFactory
            .select(club, club.memberCount)
            .from(club)
            .where(club.clubId.in(filteredClubIds))
            .orderBy(club.memberCount.desc(), club.createdAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(tuple -> new ClubWithMemberCount(tuple.get(club), tuple.get(club.memberCount)))
            .toList();
    }

    @Override
    public List<ClubWithMemberCount> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");
        BooleanBuilder condition = new BooleanBuilder();

        if (interestIds != null && !interestIds.isEmpty()) {
            condition.and(club.interest.interestId.in(interestIds));
        }
        if (city != null && !city.trim().isEmpty()) {
            condition.and(club.city.eq(city));
        }
        if (district != null && !district.trim().isEmpty()) {
            condition.and(club.district.eq(district));
        }
        if (userId != null) {
            condition.and(
                JPAExpressions.selectOne()
                        .from(excludeUserClub)
                        .where(
                                excludeUserClub.club.clubId.eq(club.clubId)
                                        .and(excludeUserClub.user.userId.eq(userId))
                        )
                        .notExists()
            );
        }

        return executeClubQuery(condition, pageable);
    }

    @Override
    public List<ClubWithMemberCount> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");

        BooleanBuilder condition = new BooleanBuilder()
                .and(club.interest.interestId.in(interestIds))
                .and(
                        JPAExpressions.selectOne()
                                .from(excludeUserClub)
                                .where(
                                        excludeUserClub.club.clubId.eq(club.clubId)
                                                .and(excludeUserClub.user.userId.eq(userId))
                                )
                                .notExists()
                );

        return executeClubQuery(condition, pageable);
    }

    @Override
    public List<ClubWithMemberCount> searchByInterest(Long interestId, Pageable pageable) {
        BooleanBuilder condition = new BooleanBuilder();
        if (interestId != null) {
            condition.and(club.interest.interestId.eq(interestId));
        }
        return executeClubQuery(condition, pageable);
    }

    @Override
    public List<ClubWithMemberCount> searchByLocation(String city, String district, Pageable pageable) {
        BooleanBuilder condition = new BooleanBuilder();
        if (city != null && !city.trim().isEmpty()) {
            condition.and(club.city.eq(city));
        }
        if (district != null && !district.trim().isEmpty()) {
            condition.and(club.district.eq(district));
        }
        return executeClubQuery(condition, pageable);
    }

    private List<ClubWithMemberCount> executeClubQuery(BooleanBuilder condition, Pageable pageable) {
        return queryFactory
                .selectFrom(club)
                .where(condition)
                .orderBy(club.memberCount.desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(c -> new ClubWithMemberCount(c, c.getMemberCount()))
                .toList();
    }
}
