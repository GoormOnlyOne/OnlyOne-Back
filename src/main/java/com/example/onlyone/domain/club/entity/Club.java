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
@Table(name = "club")
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interest_id")
    @NotNull
    private Interest interest;

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatRoom> chatRooms = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Feed> feeds = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
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
}