package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.Schedule;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "club")
@Getter
@NoArgsConstructor
public class Club extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "club_id")
    private Long clubId;

    @Column(name = "name")
    @NotNull
    private String name;

    @Column(name = "limit")
    @NotNull
    private int limit;

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

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "interest_id")
    @NotNull
    private Interest interest;

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatRoom> chatRooms = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Feed> feeds = new ArrayList<>();

    @OneToMany(mappedBy = "club", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Schedule> schedules = new ArrayList<>();

}