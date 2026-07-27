#!/bin/bash
#
# Builds and deploys FeCru-relevant Jetty artifacts to packages.atlassian.com 3dparty repository.
#
# Prerequisites:
#   - JAVA_HOME must point to JDK 1.8
#   - ~/.m2/settings.xml must contain server credentials for:
#       <id>atlassian-3rdparty</id>
#       <id>atlassian-3rdparty-snapshot</id>
#
# Repository IDs match the server IDs in settings.xml.
# Repository URLs are configured in the root pom.xml distributionManagement section.
#
# FeCru-relevant modules (as seen in fecru-4.9.9/lib):
#   jetty-annotations, jetty-continuation, jetty-http, jetty-io,
#   jetty-jmx, jetty-jndi, jetty-plus, jetty-security, jetty-server,
#   jetty-servlet, jetty-servlets, jetty-util, jetty-util-ajax,
#   jetty-webapp, jetty-xml
#
# Usage:
#   ./push-atlassian.sh               # build and deploy to packages.atlassian.com
#   ./push-atlassian.sh --local       # install to local ~/.m2 repository (for testing)
#   ./push-atlassian.sh --skip-build  # deploy already-built artifacts (skip mvn package)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

JETTY_VERSION=$(head -1 VERSION.txt | cut -d' ' -f1)
echo "Jetty version: $JETTY_VERSION"

# Verify JDK 1.8
if [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi

JAVA_VERSION_OUTPUT=$("$JAVA_BIN" -version 2>&1 | head -1)
# Handles both: java version "1.8.0_xxx" and openjdk version "25.0.x"
JAVA_VERSION=$(echo "$JAVA_VERSION_OUTPUT" | sed 's/.*version "\([^"]*\)".*/\1/')
JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f1)
if [ "$JAVA_MAJOR" = "1" ]; then
  JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f2)
fi
if [ "$JAVA_MAJOR" != "8" ]; then
  echo "WARNING: Expected JDK 8, but found JDK $JAVA_MAJOR ($JAVA_VERSION)"
  echo "Set JAVA_HOME to a JDK 1.8 installation."
  read -p "Continue anyway? (y/N): " confirm
  [ "$confirm" = "y" ] || exit 1
fi

# Parse arguments
LOCAL_INSTALL=false
for arg in "$@"; do
  case "$arg" in
    --local)  LOCAL_INSTALL=true ;;
    *)
      echo "Unknown argument: $arg"
      echo "Usage: $0 [--local]"
      exit 1
      ;;
  esac
done

# FeCru-relevant Jetty modules
FECRU_MODULES=(
  "jetty-annotations"
  "jetty-continuation"
  "jetty-http"
  "jetty-io"
  "jetty-jmx"
  "jetty-jndi"
  "jetty-plus"
  "jetty-security"
  "jetty-server"
  "jetty-servlet"
  "jetty-servlets"
  "jetty-util"
  "jetty-util-ajax"
  "jetty-webapp"
  "jetty-xml"
)

MVN=${MAVEN_BIN:-$(which mvn)}
if [ -z "$MVN" ]; then
  echo "ERROR: mvn not found. Add mvn to PATH or set MAVEN_BIN."
  exit 1
fi
echo "Using Maven: $MVN"

# Build comma-separated module list for -pl
MODULE_LIST=$(IFS=,; echo "${FECRU_MODULES[*]}")

echo ""
echo "========================================="
if [ "$LOCAL_INSTALL" = true ]; then
  echo "Installing artifacts to local Maven repository..."
  echo "Target: ~/.m2/repository"
  DEPLOY_CMD="install"
else
  echo "Deploying artifacts to packages.atlassian.com 3dparty..."
  echo "Target: https://packages.atlassian.com/artifactory/maven-3rdparty-local"
  DEPLOY_CMD="deploy"
fi
echo "========================================="

# Single reactor invocation with -am (also make dependencies) so inter-module dependencies
# are resolved from the reactor rather than the remote repo.
# -U forces update check so cached "not found" failures are cleared.
"$MVN" $DEPLOY_CMD \
  -pl "$MODULE_LIST" \
  -am \
  -DskipTests \
  -Dmaven.javadoc.skip=true \
  -U

EXIT_CODE=$?

echo ""
echo "========================================="
if [ $EXIT_CODE -eq 0 ]; then
  echo "SUCCESS: All FeCru Jetty modules published."
  echo ""
  echo "Published modules (version ${JETTY_VERSION}-atlassian-1-SNAPSHOT):"
  for module in "${FECRU_MODULES[@]}"; do
    echo "  org.eclipse.jetty:${module}"
  done
else
  echo "FAILED: Maven exited with code $EXIT_CODE. Check output above."
  exit $EXIT_CODE
fi
