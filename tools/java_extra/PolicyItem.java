// @imports
import java.util.ArrayList;
import java.util.Arrays;
// @class
    /** {@code PolicyItem.users("alice").accesses("select")} */
    public static Builder users(String... users) {
        return builder().users(Arrays.asList(users));
    }

    /** {@code PolicyItem.groups("analyst").accesses("select")} */
    public static Builder groups(String... groups) {
        return builder().groups(Arrays.asList(groups));
    }

    /** {@code PolicyItem.roles("auditors").accesses("select")} */
    public static Builder roles(String... roles) {
        return builder().roles(Arrays.asList(roles));
    }
// @builder
        /** Allowed access types, e.g. {@code accesses("select", "update")}. */
        public Builder accesses(String... types) {
            List<PolicyItemAccess> list = new ArrayList<>();
            for (String t : types) {
                list.add(PolicyItemAccess.of(t));
            }
            this.accesses = list;
            return this;
        }

        public Builder andUsers(String... more) {
            this.users = append(this.users, more);
            return this;
        }

        public Builder andGroups(String... more) {
            this.groups = append(this.groups, more);
            return this;
        }

        public Builder andRoles(String... more) {
            this.roles = append(this.roles, more);
            return this;
        }

        public Builder condition(String type, String... values) {
            if (this.conditions == null) {
                this.conditions = new ArrayList<>();
            }
            this.conditions.add(PolicyItemCondition.builder().type(type).values(Arrays.asList(values)).build());
            return this;
        }

        private static List<String> append(List<String> current, String[] more) {
            List<String> out = current == null ? new ArrayList<>() : new ArrayList<>(current);
            out.addAll(Arrays.asList(more));
            return out;
        }
