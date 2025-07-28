package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "interest")
@Getter
@Setter
@NoArgsConstructor
public class Interest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interest_id", updatable = false)
    private Long interestId;

    @Column(name = "category")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Category category;

}