package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
