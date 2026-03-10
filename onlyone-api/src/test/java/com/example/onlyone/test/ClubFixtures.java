package com.example.onlyone.test;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.user.entity.User;

public final class ClubFixtures {

    private ClubFixtures() {
    }

    public static Interest.InterestBuilder anInterest() {
        return Interest.builder()
                .interestId(1L)
                .category(Category.CULTURE);
    }

    public static Club.ClubBuilder aClub() {
        return Club.builder()
                .clubId(1L)
                .name("테스트 모임")
                .userLimit(10)
                .description("테스트 모임 설명")
                .clubImage("club.jpg")
                .city("서울")
                .district("강남구")
                .memberCount(1L)
                .interest(anInterest().build());
    }

    public static Club.ClubBuilder aClub(Long id) {
        return aClub()
                .clubId(id)
                .name("테스트 모임" + id);
    }

    public static UserClub.UserClubBuilder aUserClub(User user, Club club, ClubRole role) {
        return UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(role);
    }

    public static ClubRequestDto aClubRequestDto() {
        return new ClubRequestDto(
                "테스트 모임", 10, "테스트 모임 설명", "club.jpg", "서울", "강남구", "EXERCISE");
    }
}
