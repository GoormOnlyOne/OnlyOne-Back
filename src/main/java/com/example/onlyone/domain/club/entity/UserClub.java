package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "user_club", indexes = {
    @Index(name = "idx_user_club_user", columnList = "user_id"),
    @Index(name = "idx_user_club_club", columnList = "club_id"),
    @Index(name = "idx_user_club_user_club", columnList = "user_id, club_id")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserClub extends BaseTimeEntity  {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_club_id", updatable = false)
    private Long userClubId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @NotNull
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    @NotNull
    private Club club;

    @Column(name = "role")
    @NotNull
    @Enumerated(EnumType.STRING)
    private ClubRole clubRole;
}