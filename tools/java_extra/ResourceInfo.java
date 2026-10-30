// @class
    /** A resource by its RRN, e.g. {@code ResourceInfo.named("table:sales/orders")}. */
    public static ResourceInfo named(String rrn) {
        return builder().name(rrn).build();
    }

    /** {@code ResourceInfo.of("database", "sales", "table", "orders")} gives {@code table:sales/orders}. */
    public static ResourceInfo of(String... keyValues) {
        return named(ResourceNames.of(keyValues));
    }
