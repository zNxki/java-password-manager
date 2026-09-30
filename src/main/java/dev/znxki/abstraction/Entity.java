package dev.znxki.abstraction;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface Entity<T> {
    @Contract(pure = true)
    static <T> @Nullable T sqlMapper(@NotNull ResultSet rs) throws SQLException {
        return null;
    }
}
