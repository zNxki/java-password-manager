package dev.znxki;

import dev.znxki.database.DatabaseManager;
import dev.znxki.models.PasswordEntity;
import dev.znxki.security.SecretCipher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletionException;

public final class Main {
    private static final String CIPHER_CONTEXT = "user";
    private static DatabaseManager database;

    static void main() throws Exception {
        database = new DatabaseManager();
        SecretCipher cipher = SecretCipher.fromEnv("CRYPTO_KEYS", "CRYPTO_ACTIVE_KEY");
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(database::shutdown));
        Scanner scanner = new Scanner(System.in);

        try {
            while (true) {
                clear();
                System.out.println("""
                        
                        1. Aggiungi account
                        2. Rimuovi account
                        3. Cerca account
                        4. Mostra tutti account
                        5. Esci
                        """);

                String input = ask(scanner, "Inserisci opzione", true);
                if (!isInteger(input)) {
                    System.out.println("Non è un numero!");
                    continue;
                }

                try {
                    switch (Integer.parseInt(input)) {
                        case 1 -> addAccount(scanner, cipher);
                        case 2 -> removeAccount(scanner);
                        case 3 -> searchAccounts(scanner, cipher);
                        case 4 -> listAccounts();
                        case 5 -> {
                            return;
                        }
                        default -> System.out.println("Opzione non valida!");
                    }
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    System.out.println("Errore: " + cause.getMessage());
                } catch (GeneralSecurityException e) {
                    System.out.println("Errore cifratura: " + e.getMessage());
                }

                pause(scanner);
            }
        } finally {
            database.shutdown();
        }
    }

    private static void addAccount(Scanner scanner, @NotNull SecretCipher cipher) throws GeneralSecurityException {
        String name = ask(scanner, "Inserisci nome dell'account", true);
        String username = ask(scanner, "Inserisci username", false);
        String password = ask(scanner, "Inserisci la password", true);
        String url = ask(scanner, "Inserisci url del sito", false);
        String notes = ask(scanner, "Inserisci note", false);

        String encryptedPassword = cipher.encrypt(password, CIPHER_CONTEXT);
        PasswordEntity entity = new PasswordEntity(0, name, username, encryptedPassword, url, notes);

        database.addAccount(entity).join();
        System.out.println("Account salvato con successo!");
    }

    private static void removeAccount(Scanner scanner) {
        List<PasswordEntity> accounts = database.getAccounts().join();
        if (accounts.isEmpty()) {
            System.out.println("Nessun account salvato.");
            return;
        }

        accounts.forEach(Main::printSummary);
        System.out.println();

        String input = ask(scanner, "Inserisci l'id da rimuovere", true);
        if (!isInteger(input)) {
            System.out.println("Non è un numero!");
            return;
        }

        if (!ask(scanner, "Confermi? [s/n]", true).trim().equalsIgnoreCase("s")) {
            System.out.println("Operazione annullata.");
            return;
        }

        boolean removed = database.deleteAccount(Integer.parseInt(input.trim())).join();
        System.out.println(removed ? "Account rimosso!" : "Nessun account con quell'id.");
    }

    private static void searchAccounts(Scanner scanner, SecretCipher cipher) {
        String term = ask(scanner, "Inserisci nome, username o url da cercare", true).trim();
        List<PasswordEntity> accounts = database.searchAccounts(term).join();

        if (accounts.isEmpty()) {
            System.out.println("Nessun risultato.");
            return;
        }

        for (PasswordEntity account : accounts) {
            printSummary(account);
            printField("password", reveal(cipher, account));
            printField("note", account.getNotes());
            System.out.println();
        }
    }

    private static void listAccounts() {
        List<PasswordEntity> accounts = database.getAccounts().join();

        if (accounts.isEmpty()) {
            System.out.println("Nessun account salvato.");
            return;
        }

        accounts.forEach(Main::printSummary);
    }

    private static @NotNull String reveal(@NotNull SecretCipher cipher, @NotNull PasswordEntity account) {
        try {
            return cipher.decrypt(account.getPassword(), CIPHER_CONTEXT);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "<non decifrabile: " + e.getMessage() + ">";
        }
    }

    private static void printSummary(@NotNull PasswordEntity account) {
        System.out.println("#" + account.getId() + " " + account.getName());
        printField("username", account.getUsername());
        printField("url", account.getUrl());
    }

    private static void printField(String label, @Nullable String value) {
        if (value != null && !value.isBlank())
            System.out.println("    " + label + ": " + value);
    }

    private static void pause(@NotNull Scanner scanner) {
        System.out.println();
        System.out.println("Premi invio per continuare...");
        scanner.nextLine();
    }

    private static void clear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static @Nullable String ask(@NotNull Scanner scanner, String label, boolean required) {
        String suffix = required ? "" : " ['salta' per saltare]";

        while (true) {
            System.out.println(label + suffix);
            System.out.print("> ");
            String input = scanner.nextLine();

            if (input.isBlank()) {
                System.out.println("Invalido!");
                continue;
            }

            if (!required && input.trim().equalsIgnoreCase("salta")) {
                return null;
            }

            return input;
        }
    }

    private static boolean isInteger(@NotNull String str) {
        try {
            Integer.parseInt(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}