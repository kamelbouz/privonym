package com.privonym.repository.oracle;

/** Dependency-free checks for fine-grained Discovery column classification. */
public final class OracleDiscoveryClassifierSmokeCheck {
    private OracleDiscoveryClassifierSmokeCheck() { }

    public static void main(String[] arguments) {
        require("DEPT_NAME", "ORGANIZATION_DEPARTMENT", "LOOKUP_DEPARTMENT");
        require("department_name", "ORGANIZATION_DEPARTMENT", "LOOKUP_DEPARTMENT");
        require("department_manager_name", "PERSON_LAST_NAME", "LOOKUP_LAST_NAME");
        require("first_name", "PERSON_FIRST_NAME", "LOOKUP_FIRST_NAME");
        require("prenom_client", "PERSON_FIRST_NAME", "LOOKUP_FIRST_NAME");
        require("company_name", "ORGANIZATION_NAME", "LOOKUP_COMPANY");
        require("employer", "ORGANIZATION_NAME", "LOOKUP_COMPANY");
        require("last_name", "PERSON_LAST_NAME", "LOOKUP_LAST_NAME");
        require("name", "PERSON_LAST_NAME", "LOOKUP_LAST_NAME");
        require("customer_name", "PERSON_FULL_NAME", "CONSISTENT_LOOKUP");
        require("city", "CITY", "LOOKUP_CITY");
        require("ville_residence", "CITY", "LOOKUP_CITY");
        require("home_address", "POSTAL_ADDRESS", "LOOKUP_ADDRESS");
        require("adresse_client", "POSTAL_ADDRESS", "LOOKUP_ADDRESS");
        System.out.println("Oracle Discovery classifier smoke check passed.");
    }

    private static void require(String column, String classification, String algorithm) {
        OracleDiscoveryAdapter.Match match = OracleDiscoveryAdapter.classify(column, "VARCHAR");
        if (match == null || !classification.equals(match.classification())
                || match.hints().isEmpty() || !algorithm.equals(match.hints().get(0))) {
            throw new AssertionError("Unexpected Discovery classification for " + column + ": " + match);
        }
    }
}
