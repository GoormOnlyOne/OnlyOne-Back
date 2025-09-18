package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

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
@AllArgsConstructor
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
    private Long memberCount = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interest_id")
    @NotNull
    private Interest interest;

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ChatRoom> chatRooms = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Feed> feeds = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Schedule> schedules = new ArrayList<>();

    public void update(String name,
                       int userLimit,
                       String description,
                       String clubImage,
                       String city,
                       String district,
                       Interest interest) {
        this.name = name;
        this.userLimit = userLimit;
        this.description = description;
        this.clubImage = clubImage;
        this.city = city;
        this.district = district;
        this.interest = interest;
    }

    public void addSchedule(Schedule schedule) {
        schedules.add(schedule);
    }

    public void incrementMemberCount() {
        if (this.memberCount == null) {
            this.memberCount = 1L;
        } else {
            this.memberCount++;
        }
    }
    
    public void decrementMemberCount() {
        this.memberCount = Math.max(0L, this.memberCount - 1);
    }

}