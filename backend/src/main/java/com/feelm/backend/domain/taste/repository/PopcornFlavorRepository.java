package com.feelm.backend.domain.taste.repository;

import com.feelm.backend.domain.taste.entity.PopcornFlavor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PopcornFlavorRepository extends JpaRepository<PopcornFlavor, Long> {

    List<PopcornFlavor> findAllByOrderBySortOrderAsc();
}
