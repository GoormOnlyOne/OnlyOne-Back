package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "interest")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interest_id", updatable = false)
    private Long interestId;

    @Column(name = "category")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Category category;

    @OneToMany(mappedBy = "interest")
    private List<Club> clubs = new ArrayList<>();
}