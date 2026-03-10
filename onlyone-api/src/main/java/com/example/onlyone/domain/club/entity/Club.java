package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "club", indexes = {
        @Index(name = "idx_club_interest_location", columnList = "interest_id, city, district"),
        @Index(name = "idx_club_interest", columnList = "interest_id"),
        @Index(name = "idx_club_location", columnList = "city, district"),
        @Index(name = "idx_club_member_count_created", columnList = "member_count DESC, created_at DESC"),
        @Index(name = "idx_club_interest_member_created", columnList = "interest_id, member_count DESC, created_at DESC"),
        @Index(name = "idx_club_location_member_created", columnList = "city, district, member_count DESC, created_at DESC"),
        @Index(name = "idx_club_interest_location_member_created", columnList = "interest_id, city, district, member_count DESC, created_at DESC")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Club extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "club_id")
    private Long clubId;

    @Column(name = "name")
    @NotNull
    private String name;

    @Column(name = "user_limit")
    @NotNull
    private int userLimit;

    @Column(name = "description")
    @NotNull
    private String description;

    @Column(name = "club_image")
    private String clubImage;

    @Column(name = "city")
    @NotNull
    private String city;

    @Column(name = "district")
    @NotNull
    private String district;

    @Column(name = "member_count", nullable = false)
    @NotNull
    @Builder.Default
    private Long memberCount = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interest_id")
    @NotNull
    private Interest interest;

    public void update(ClubUpdateCommand cmd) {
        this.name = cmd.name();
        this.userLimit = cmd.userLimit();
        this.description = cmd.description();
        this.clubImage = cmd.clubImage();
        this.city = cmd.city();
        this.district = cmd.district();
        this.interest = cmd.interest();
    }

}