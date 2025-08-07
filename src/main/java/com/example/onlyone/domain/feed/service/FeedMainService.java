package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;


@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class FeedMainService {
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final UserClubRepository userClubRepository;

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();

        List<UserClub> myJoinClubs = userClubRepository.findByUserUserId(userId);

        List<Long> myClubIds = myJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .toList();

        List<Long> memberIds = userClubRepository.findUserIdByClubIds(myClubIds);

        List<UserClub> friendMemberJoinClubs = userClubRepository.findByUserUserIdIn(memberIds);

        List<Long> friendClubIds = friendMemberJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .filter(id -> !myClubIds.contains(id))
                .toList();

        List<Long> allClubIds = new ArrayList<>(myClubIds);
        allClubIds.addAll(friendClubIds);

        List<Feed> feeds = feedRepository.findByClubIds(allClubIds, pageable);

        return feeds.stream()
                .map(feed -> FeedOverviewDto.builder()
                        .feedId(feed.getFeedId())
                        .thumbnailUrl(feed.getFeedImages().get(0).getFeedImage())
                        .likeCount(feed.getFeedLikes().size())
                        .commentCount(feed.getFeedComments().size())
                        .profileImage(feed.getUser().getProfileImage())
                        .build()
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();

        List<UserClub> myJoinClubs = userClubRepository.findByUserUserId(userId);

        List<Long> myClubIds = myJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .toList();

        List<Long> memberIds = userClubRepository.findUserIdByClubIds(myClubIds);

        List<UserClub> friendMemberJoinClubs = userClubRepository.findByUserUserIdIn(memberIds);

        List<Long> friendClubIds = friendMemberJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .filter(id -> !myClubIds.contains(id))
                .toList();

        List<Long> allClubIds = new ArrayList<>(myClubIds);
        allClubIds.addAll(friendClubIds);

        List<Feed> feeds = feedRepository.findPopularByClubIds(allClubIds, pageable);

        return feeds.stream()
                .map(f -> FeedOverviewDto.builder()
                        .feedId(f.getFeedId())
                        .thumbnailUrl(f.getFeedImages().get(0).getFeedImage())
                        .likeCount(f.getFeedLikes().size())
                        .commentCount(f.getFeedComments().size())
                        .profileImage(f.getUser().getProfileImage())
                        .created(f.getCreatedAt())
                        .build()
                )
                .toList();
    }
}
