package com.example.onlyone.test;

import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

import java.time.LocalDate;

public final class UserFixtures {

    private UserFixtures() {
    }

    public static User.UserBuilder aUser() {
        return User.builder()
                .userId(1L)
                .kakaoId(10001L)
                .nickname("테스트유저")
                .birth(LocalDate.of(1995, 1, 1))
                .status(Status.ACTIVE)
                .profileImage("profile.jpg")
                .gender(Gender.MALE)
                .city("서울")
                .district("강남구")
                .role(Role.ROLE_USER);
    }

    public static User.UserBuilder aUser(Long id) {
        return aUser()
                .userId(id)
                .kakaoId(10000L + id)
                .nickname("테스트유저" + id);
    }
}
