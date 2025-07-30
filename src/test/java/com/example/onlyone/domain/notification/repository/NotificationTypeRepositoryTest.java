package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationTypeRepositoryTest {

    @Autowired
    private NotificationTypeRepository typeRepo;

    @Test
    @DisplayName("NotificationType 저장 후 findByType 으로 조회된다")
    void saveAndFindByType() {
        // given
        NotificationType nt = new NotificationType(Type.LIKE, "회원 %s님이 좋아요를 눌렀습니다.");
        NotificationType saved = typeRepo.save(nt);

        // when
        Optional<NotificationType> loaded = typeRepo.findByType(Type.LIKE);

        // then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getType()).isEqualTo(Type.LIKE);
        assertThat(loaded.get().getTemplate())
                .isEqualTo("회원 %s님이 좋아요를 눌렀습니다.");
        assertThat(loaded.get().getTypeId()).isEqualTo(saved.getTypeId());
    }
}