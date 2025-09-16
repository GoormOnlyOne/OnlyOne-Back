package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.QClub;
import com.example.onlyone.domain.club.entity.QUserClub;
import com.example.onlyone.domain.interest.entity.QInterest;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ClubRepositoryImpl implements ClubRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QClub club = QClub.club;
    private static final QUserClub userClub = QUserClub.userClub;
    private static final QInterest interest = QInterest.interest;

    @Override
    public List<Object[]> searchByKeywordWithFilter(SearchFilterDto filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);

        // WHERE 조건 구성 - 선택도가 높은 조건부터 적용
        BooleanBuilder whereCondition = new BooleanBuilder();

        // 1. 지역 필터 (가장 선택도가 높음)
        if (filter.hasLocation()) {
            whereCondition.and(club.city.eq(filter.getCity().trim()))
                         .and(club.district.eq(filter.getDistrict().trim()));
        }

        // 2. 관심사 필터
        if (filter.getInterestId() != null) {
            whereCondition.and(club.interest.interestId.eq(filter.getInterestId()));
        }

        // 3. 키워드 검색 - MySQL FULLTEXT MATCH AGAINST 사용 (가장 마지막)
        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            whereCondition.and(
                fullTextMatchTemplate(keyword).gt(4.0)
            );
        }

        // ORDER BY 조건 구성 - memberCount 컬럼 직접 사용
        OrderSpecifier<?>[] orderSpecifiers = createOrderSpecifiersWithColumn(filter);

        // 쿼리 실행 - JOIN 제거, GROUP BY 제거!
        return queryFactory
            .select(
                club.clubId,
                club.name,
                club.description,
                club.district,
                club.clubImage,
                interest.category,
                club.memberCount
            )
            .from(club)
            .join(interest).on(club.interest.interestId.eq(interest.interestId)) // INNER JOIN만
            .where(whereCondition)
            .orderBy(orderSpecifiers)
            .offset(pageRequest.getOffset())
            .limit(pageRequest.getPageSize())
            .fetch()
            .stream()
            .map(tuple -> new Object[] {
                tuple.get(club.clubId),
                tuple.get(club.name),
                tuple.get(club.description),
                tuple.get(club.district),
                tuple.get(club.clubImage),
                tuple.get(interest.category).name(), // Category enum을 String으로 변환
                tuple.get(club.memberCount)
            })
            .toList(); // List<Object[]> 반환
    }

    @Override
    public List<Object[]> findClubsByTeammates(Long userId, Pageable pageable) {
        // QueryDSL 별칭 정의
        QUserClub uc1 = new QUserClub("uc1");
        QUserClub teammate = new QUserClub("teammate");
        QUserClub uc2 = new QUserClub("uc2");
        QUserClub uc3 = new QUserClub("uc3");

        return queryFactory
            .select(
                club,
                club.memberCount  // 컬럼 직접 사용
            )
            .from(club)
            // LEFT JOIN 제거 - memberCount 컬럼 사용으로 불필요
            .where(
                // EXISTS: 현재 사용자와 함께 참여한 모임이 있는 다른 사용자들이 참여한 모임
                JPAExpressions.selectOne()
                    .from(uc1)
                    .join(teammate).on(uc1.club.clubId.eq(teammate.club.clubId))
                    .join(uc2).on(teammate.user.userId.eq(uc2.user.userId))
                    .where(
                        uc2.club.clubId.eq(club.clubId)
                            .and(uc1.user.userId.eq(userId))
                            .and(teammate.user.userId.ne(userId))
                    )
                    .exists(),
                // NOT EXISTS: 현재 사용자가 참여하지 않은 모임
                JPAExpressions.selectOne()
                    .from(uc3)
                    .where(
                        uc3.club.clubId.eq(club.clubId)
                            .and(uc3.user.userId.eq(userId))
                    )
                    .notExists()
            )
            // GROUP BY 제거 - 집계 함수 사용하지 않음
            .orderBy(club.memberCount.desc(), club.createdAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(tuple -> new Object[] {
                tuple.get(club),
                tuple.get(club.memberCount)  // 컬럼 직접 사용
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

    private NumberExpression<Double> fullTextMatchTemplate(String searchKeyword) {
        return Expressions.numberTemplate(Double.class,
                "function('match', {0}, {1}, {2})",
                club.name, club.description, searchKeyword);
    }


    private OrderSpecifier<?>[] createOrderSpecifiersWithColumn(SearchFilterDto filter) {
        // memberCount 컬럼을 직접 사용한 정렬
        
        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            // 키워드가 있을 때는 FULLTEXT 관련성 점수로 정렬
            return new OrderSpecifier[] {
                fullTextMatchTemplate(keyword).desc(),
                club.memberCount.desc()
            };
        } else {
            // 키워드가 없을 때는 sortBy 조건에 따라 정렬
            if (filter.getSortBy() == SearchFilterDto.SortType.LATEST) {
                return new OrderSpecifier[] {
                    club.createdAt.desc()
                };
            } else {
                // MEMBER_COUNT일 때는 컬럼 직접 사용
                return new OrderSpecifier[] {
                    club.memberCount.desc(),
                    club.createdAt.desc()
                };
            }
        }
    }
    
    // 기존 메서드도 유지 (다른 곳에서 사용 중일 수 있음)
    private OrderSpecifier<?>[] createOrderSpecifiers(SearchFilterDto filter, NumberExpression<Long> memberCountExpr) {
        NumberExpression<Double> sortExpression;

        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            sortExpression = fullTextMatchTemplate(keyword);
        } else {
            if (filter.getSortBy() == SearchFilterDto.SortType.LATEST) {
                sortExpression = Expressions.numberTemplate(Double.class,
                    "UNIX_TIMESTAMP({0})", club.createdAt
                );
            } else {
                sortExpression = memberCountExpr.doubleValue();
            }
        }

        return new OrderSpecifier[] {
            sortExpression.desc(),
            club.createdAt.desc()
        };
    }
}
