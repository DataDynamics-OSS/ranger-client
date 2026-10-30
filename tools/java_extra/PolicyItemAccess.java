// @class
    public static PolicyItemAccess of(String type) {
        return builder().type(type).allowed(true).build();
    }
