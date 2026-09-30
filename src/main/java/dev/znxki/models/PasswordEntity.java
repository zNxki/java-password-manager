package dev.znxki.models;

import dev.znxki.abstraction.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;

@Getter
@Setter
@AllArgsConstructor
@RequiredArgsConstructor
public class PasswordEntity implements Entity<PasswordEntity> {
    private int id;
    private final String name;
    private String username;
    private final String password;
    private String url;
    private String notes;

    public static PasswordEntity sqlMapper(@NotNull ResultSet rs) throws SQLException {
        return new PasswordEntity(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getString("url"),
                rs.getString("notes")
        );
    }
}
