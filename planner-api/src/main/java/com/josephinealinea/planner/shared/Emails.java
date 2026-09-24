package com.josephinealinea.planner.shared;

/** Email-address rules shared by the account and display code. */
public final class Emails {

    /** How much of an address is shown before the rest is replaced by an ellipsis. */
    public static final int DISPLAY_LENGTH = 37;

    /** The address a deactivated account is given; the id makes it unique and unguessable. */
    public static String deactivated(String userId) {
        return "deactivated-" + userId + "@example.com";
    }

    /**
     * The first 37 characters of an address plus an ellipsis, or the address
     * itself when it is no longer than that. Display only: never stored or used
     * for sending mail. This is the only place the rule lives: the pages show
     * what the API sends (displayName, MemberView.emailLabel).
     */
    public static String shorten(String email) {
        if (email == null || email.length() <= DISPLAY_LENGTH) return email;
        return email.substring(0, DISPLAY_LENGTH) + "…";
    }

    private Emails() {}
}
