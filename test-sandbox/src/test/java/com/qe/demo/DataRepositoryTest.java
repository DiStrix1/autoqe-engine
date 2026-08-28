package com.qe.demo;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DataRepositoryTest {

    private DataRepository<String> dataRepository;

    @BeforeEach
    void setUp() {
        dataRepository = new DataRepository<>();
    }

    @Test
    void testSave() {
        // Given
        String id = "id1";
        String item = "item1";

        // When
        dataRepository.save(id, item);

        // Then
        assertEquals(1, dataRepository.count());
        Optional<String> found = dataRepository.findById(id);
        assertTrue(found.isPresent());
        assertEquals(item, found.get());
    }

    @Test
    void testSaveNullId() {
        assertThrows(IllegalArgumentException.class, () -> dataRepository.save(null, "item1"));
    }

    @Test
    void testSaveBlankId() {
        assertThrows(IllegalArgumentException.class, () -> dataRepository.save("   ", "item1"));
    }

    @Test
    void testSaveNullItem() {
        assertThrows(IllegalArgumentException.class, () -> dataRepository.save("id1", null));
    }

    @Test
    void testFindById() {
        // Given
        dataRepository.save("id1", "testData");

        // When
        Optional<String> found = dataRepository.findById("id1");

        // Then
        assertTrue(found.isPresent());
        assertEquals("testData", found.get());
    }

    @Test
    void testFindByIdNotFound() {
        // When & Then
        Optional<String> found = dataRepository.findById("non-existent-id");
        assertFalse(found.isPresent());
    }

    @Test
    void testFindAll() {
        // Given
        dataRepository.save("id1", "item1");
        dataRepository.save("id2", "item2");

        // When
        List<String> all = dataRepository.findAll();

        // Then
        assertEquals(2, all.size());
        assertTrue(all.contains("item1"));
        assertTrue(all.contains("item2"));
    }

    @Test
    void testDeleteById() {
        // Given
        dataRepository.save("id1", "item1");

        // When & Then
        assertTrue(dataRepository.deleteById("id1"));
        assertEquals(0, dataRepository.count());
        assertFalse(dataRepository.findById("id1").isPresent());
    }

    @Test
    void testDeleteByIdNotFound() {
        // When & Then
        assertFalse(dataRepository.deleteById("non-existent-id"));
        assertEquals(0, dataRepository.count());
    }

    @Test
    void testClear() {
        // Given
        dataRepository.save("id1", "item1");
        dataRepository.save("id2", "item2");

        // When
        dataRepository.clear();

        // Then
        assertEquals(0, dataRepository.count());
        assertTrue(dataRepository.findAll().isEmpty());
    }

    @Test
    void testCount() {
        // Given
        assertEquals(0, dataRepository.count());

        dataRepository.save("id1", "item1");
        assertEquals(1, dataRepository.count());

        dataRepository.save("id2", "item2");
        assertEquals(2, dataRepository.count());
    }
}
