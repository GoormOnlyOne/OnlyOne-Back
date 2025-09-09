package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "notification_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationType extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "type_id", updatable = false)
    private Long id;

    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private Type type;

    @Column(name = "template", nullable = false)
    private String template;

    private NotificationType(Type type, String template) {
        this.type = type;
        this.template = template;
    }

    public static NotificationType of(Type type, String template) {
        return new NotificationType(type, template);
    }

    public String render(String... args) {
        if (args == null || args.length == 0) {
            return template;
        }

        return String.format(template, (Object[]) args);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NotificationType that)) return false;
        
        // 두 엔티티 모두 id가 null인 경우 (아직 영속화되지 않은 경우)
        if (id == null && that.id == null) {
            return false; // 서로 다른 transient 객체로 간주
        }
        
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        // id가 null인 경우에도 일관된 hashCode 반환
        return id != null ? Objects.hash(id) : getClass().hashCode();
    }

    @Override
    public String toString() {
        return String.format("NotificationType{id=%s, type=%s}", 
                id, type);
    }
}