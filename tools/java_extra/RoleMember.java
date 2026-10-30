// @class
    public static RoleMember of(String name) {
        return builder().name(name).admin(false).build();
    }

    public static RoleMember admin(String name) {
        return builder().name(name).admin(true).build();
    }
