package com.example.onlyone.domain.club.document;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Category;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.annotations.DateFormat;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

@Document(indexName = "clubs")
@Setting(settingPath = "/elasticsearch/club-settings.json")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClubDocument {

    @Id
    private Long clubId;

    @Field(type = FieldType.Text, searchAnalyzer = "nori_with_stopwords")
    private String name;

    @Field(type = FieldType.Text, searchAnalyzer = "nori_with_stopwords")
    private String description;

    @Field(type = FieldType.Keyword)
    private String city;

    @Field(type = FieldType.Keyword)
    private String district;

    @Field(type = FieldType.Keyword)
    private String clubImage;

    @Field(type = FieldType.Long)
    private Long memberCount;

    @Field(type = FieldType.Long)
    private Long interestId;

    @Field(type = FieldType.Keyword)
    private String interestCategory;

    @Field(type = FieldType.Keyword)
    private String interestKoreanName;

    @Field(type = FieldType.Date, 
           pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS||yyyy-MM-dd'T'HH:mm:ss.SSS||yyyy-MM-dd'T'HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSSSSS][.SSS]")
    private LocalDateTime createdAt;

    @Field(type = FieldType.Text, searchAnalyzer = "nori_with_stopwords")
    private String searchText;

    public static ClubDocument from(Club club) {
        Category category = club.getInterest().getCategory();
        
        return ClubDocument.builder()
                .clubId(club.getClubId())
                .name(club.getName())
                .description(club.getDescription())
                .city(club.getCity())
                .district(club.getDistrict())
                .clubImage(club.getClubImage())
                .memberCount(club.getMemberCount())
                .interestId(club.getInterest().getInterestId())
                .interestCategory(category.name())
                .interestKoreanName(category.getKoreanName())
                .createdAt(club.getCreatedAt())
                .searchText(club.getName() + " " + club.getDescription())
                .build();
    }
}