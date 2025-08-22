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

    @Column(name = "delivery_method", nullable = false)
    @Enumerated(EnumType.STRING)
    private DeliveryMethod deliveryMethod;


    private NotificationType(Type type, String template, DeliveryMethod deliveryMethod) {
        this.type = type;
        this.template = template;
        this.deliveryMethod = deliveryMethod;
    }

    public static NotificationType of(Type type, String template) {
        return new NotificationType(type, template, DeliveryMethod.getOptimalMethod(type));
    }


    public String render(String... args) {
        if (args == null || args.length == 0) {
            return template; //  실행이 에러를 여깃 던지나
        }
        return String.format(template, (Object[]) args);

        //금액 숫자 -> 문자열로 파싱하는 것에 대한 비용
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NotificationType that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("NotificationType{id=%d, type=%s, deliveryMethod=%s}", 
                id, type, deliveryMethod);
    }
}