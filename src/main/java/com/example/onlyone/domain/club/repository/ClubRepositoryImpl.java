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
        // 지역이나 관심사가 있으면 서브쿼리 방식
        if (filter.hasLocation() || filter.getInterestId() != null) {
            return searchWithSubquery(filter, page, size);
        }
        // 키워드만 있으면 단일 쿼리 (FULLTEXT 사용)
        else {
            return searchKeywordOnly(filter, page, size);
        }
    }
    
    private List<Object[]> searchWithSubquery(SearchFilterDto filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        
        // 1단계: 지역/관심사로 club ID 필터링
        BooleanBuilder preFilterCondition = new BooleanBuilder();
        
        if (filter.hasLocation()) {
            preFilterCondition.and(club.city.eq(filter.getCity().trim()))
                             .and(club.district.eq(filter.getDistrict().trim()));
        }
        
        if (filter.getInterestId() != null) {
            preFilterCondition.and(club.interest.interestId.eq(filter.getInterestId()));
        }
        
        List<Long> clubIds = queryFactory
            .select(club.clubId)
            .from(club)
            .where(preFilterCondition)
            .fetch();
            
        if (clubIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 2단계: 필터링된 club들에 대해 FULLTEXT 검색 및 JOIN
        BooleanBuilder finalCondition = new BooleanBuilder();
        finalCondition.and(club.clubId.in(clubIds));
        
        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            finalCondition.and(fullTextMatchTemplate(keyword).gt(0));
        }
        
        NumberExpression<Long> memberCountExpr = userClub.userClubId.count();
        OrderSpecifier<?>[] orderSpecifiers = createOrderSpecifiers(filter, memberCountExpr);
        
        return queryFactory
            .select(
                club.clubId,
                club.name,
                club.description,
                club.district,
                club.clubImage,
                interest.category,
                memberCountExpr
            )
            .from(club)
            .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
            .leftJoin(interest).on(club.interest.interestId.eq(interest.interestId))
            .where(finalCondition)
            .groupBy(club.clubId, club.name, club.description, club.district, club.clubImage, interest.category)
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
                tuple.get(interest.category).name(),
                tuple.get(memberCountExpr)
            })
            .toList();
    }
    
    private List<Object[]> searchKeywordOnly(SearchFilterDto filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        
        BooleanBuilder whereCondition = new BooleanBuilder();
        
        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            whereCondition.and(fullTextMatchTemplate(keyword).gt(0));
        }
        
        NumberExpression<Long> memberCountExpr = userClub.userClubId.count();
        OrderSpecifier<?>[] orderSpecifiers = createOrderSpecifiers(filter, memberCountExpr);
        
        return queryFactory
            .select(
                club.clubId,
                club.name,
                club.description,
                club.district,
                club.clubImage,
                interest.category,
                memberCountExpr
            )
            .from(club)
            .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
            .leftJoin(interest).on(club.interest.interestId.eq(interest.interestId))
            .where(whereCondition)
            .groupBy(club.clubId, club.name, club.description, club.district, club.clubImage, interest.category)
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
                tuple.get(interest.category).name(),
                tuple.get(memberCountExpr)
            })
            .toList();
    }
    
    @Override
    public List<Object[]> findClubsByTeammates(Long userId, Pageable pageable) {
        // QueryDSL 별칭 정의
        QUserClub uc1 = new QUserClub("uc1");
        QUserClub teammate = new QUserClub("teammate");
        QUserClub uc2 = new QUserClub("uc2");
        QUserClub uc3 = new QUserClub("uc3");
        
        // 멤버 수 카운트
        NumberExpression<Long> memberCountExpr = userClub.userClubId.count();
        
        return queryFactory
            .select(
                club,
                memberCountExpr
            )
            .from(club)
            .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
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
            .groupBy(club.clubId)
            .orderBy(memberCountExpr.desc(), club.createdAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(tuple -> new Object[] {
                tuple.get(club),
                tuple.get(memberCountExpr)
            })
            .toList();
    }

    @Override
    public List<Object[]> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");
        
        return queryFactory
                .select(club, userClub.userClubId.count())
                .from(club)
                .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
                .where(
                        club.interest.interestId.in(interestIds)
                                .and(club.city.eq(city))
                                .and(club.district.eq(district))
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
                .groupBy(club.clubId)
                .orderBy(userClub.userClubId.count().desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(tuple -> new Object[] {
                        tuple.get(club),
                        tuple.get(userClub.userClubId.count())
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable) {
        QUserClub excludeUserClub = new QUserClub("excludeUserClub");
        
        return queryFactory
                .select(club, userClub.userClubId.count())
                .from(club)
                .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
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
                .groupBy(club.clubId)
                .orderBy(userClub.userClubId.count().desc(), club.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(tuple -> new Object[] {
                        tuple.get(club),
                        tuple.get(userClub.userClubId.count())
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByInterest(Long interestId, Pageable pageable) {
        return queryFactory
                .select(club, userClub.userClubId.count())
                .from(club)
                .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
                .where(club.interest.interestId.eq(interestId))
                .groupBy(club.clubId)
                .orderBy(userClub.userClubId.count().desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(tuple -> new Object[] {
                        tuple.get(club),
                        tuple.get(userClub.userClubId.count())
                })
                .toList();
    }

    @Override
    public List<Object[]> searchByLocation(String city, String district, Pageable pageable) {
        return queryFactory
                .select(club, userClub.userClubId.count())
                .from(club)
                .leftJoin(userClub).on(club.clubId.eq(userClub.club.clubId))
                .where(club.city.eq(city).and(club.district.eq(district)))
                .groupBy(club.clubId)
                .orderBy(userClub.userClubId.count().desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(tuple -> new Object[] {
                        tuple.get(club),
                        tuple.get(userClub.userClubId.count())
                })
                .toList();
    }
    
    private NumberExpression<Double> fullTextMatchTemplate(String searchKeyword) {
        return Expressions.numberTemplate(Double.class,
                "function('match', {0}, {1}, {2})", 
                club.name, club.description, searchKeyword);
    }
    
    private OrderSpecifier<?>[] createOrderSpecifiers(SearchFilterDto filter, NumberExpression<Long> memberCountExpr) {
        // 기존 네이티브 쿼리와 동일한 CASE 구문을 사용한 정렬
        NumberExpression<Double> sortExpression;
        
        if (filter.hasKeyword()) {
            String keyword = filter.getKeyword().trim();
            // 키워드가 있을 때는 FULLTEXT 관련성 점수로 정렬
            sortExpression = fullTextMatchTemplate(keyword);
        } else {
            // 키워드가 없을 때는 sortBy 조건에 따라 정렬
            if (filter.getSortBy() == SearchFilterDto.SortType.LATEST) {
                // LATEST일 때는 created_at을 숫자로 변환하여 정렬 (UNIX_TIMESTAMP 사용)
                sortExpression = Expressions.numberTemplate(Double.class,
                    "UNIX_TIMESTAMP({0})", club.createdAt
                );
            } else {
                // MEMBER_COUNT일 때는 멤버 수로 정렬
                sortExpression = memberCountExpr.doubleValue();
            }
        }
        
        return new OrderSpecifier[] {
            sortExpression.desc(),
            club.createdAt.desc() // 2차 정렬은 항상 최신순
        };
    }
}
