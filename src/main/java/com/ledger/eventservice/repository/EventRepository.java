package com.ledger.eventservice.repository;

import com.ledger.eventservice.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    List<Event> findAllByAccountIdOrderByEventTimestampAsc(String accountId);
}
