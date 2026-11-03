package bo.edu.caseapp.generator;

public final class GeneratorRequests {
  private GeneratorRequests() {}

  public record GenerateConfig(
      String packageName,
      String projectName,
      Integer serverPort,
      String databaseName,
      String databaseUser,
      String databasePassword,
      Boolean includeFrontend,
      Boolean includeFlutter
  ) {
    public String resolvedPackage() {
      return packageName != null && !packageName.isBlank() ? packageName.trim() : "bo.edu.generated";
    }

    public String resolvedProjectName() {
      return projectName != null && !projectName.isBlank() ? projectName.trim() : "generated-app";
    }

    public int resolvedPort() {
      return serverPort != null && serverPort > 0 ? serverPort : 8081;
    }

    public String resolvedDbName() {
      return databaseName != null && !databaseName.isBlank() ? databaseName.trim() : "app_db";
    }

    public String resolvedDbUser() {
      return databaseUser != null && !databaseUser.isBlank() ? databaseUser.trim() : "postgres";
    }

    public String resolvedDbPassword() {
      return databasePassword != null ? databasePassword : "password";
    }

    public boolean resolvedIncludeFrontend() {
      return includeFrontend == null || includeFrontend;
    }

    public boolean resolvedIncludeFlutter() {
      return includeFlutter != null && includeFlutter;
    }
  }
}
