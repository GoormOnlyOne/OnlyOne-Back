package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.NotificationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NotificationMongoRepository extends MongoRepository<NotificationDocument, String> {

    List<NotificationDocument> findByUserIdOrderByIdDesc(Long userId);
}
