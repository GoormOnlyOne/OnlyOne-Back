package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "interest")
@BatchSize(size = 8)
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
}