// @imports
import java.util.ArrayList;
import java.util.LinkedHashMap;
// @class
    public static final int TYPE_ACCESS = 0;
    public static final int TYPE_DATAMASK = 1;
    public static final int TYPE_ROWFILTER = 2;
// @builder
        /** {@code resource("database", "sales")}: resources keep insertion order. */
        public Builder resource(String type, String... values) {
            return resource(type, PolicyResource.of(values));
        }

        public Builder resource(String type, PolicyResource resource) {
            if (this.resources == null) {
                this.resources = new LinkedHashMap<>();
            }
            this.resources.put(type, resource);
            return this;
        }

        /** Add an allow item. */
        public Builder allow(PolicyItem item) {
            if (this.policyItems == null) {
                this.policyItems = new ArrayList<>();
            }
            this.policyItems.add(item);
            return this;
        }

        public Builder allow(PolicyItem.Builder item) {
            return allow(item.build());
        }

        /** Add a deny item. */
        public Builder deny(PolicyItem item) {
            if (this.denyPolicyItems == null) {
                this.denyPolicyItems = new ArrayList<>();
            }
            this.denyPolicyItems.add(item);
            return this;
        }

        public Builder deny(PolicyItem.Builder item) {
            return deny(item.build());
        }

        public Builder label(String label) {
            if (this.policyLabels == null) {
                this.policyLabels = new ArrayList<>();
            }
            if (!this.policyLabels.contains(label)) {
                this.policyLabels.add(label);
            }
            return this;
        }
