#!/usr/bin/env bash
# =============================================================================
# QE-RAG System — Developer CLI Script (Bash)
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

ACTION="${1:-help}"

case "$ACTION" in
  up)
    echo "Starting Docker services..."
    cd "$PROJECT_ROOT"
    docker compose up -d
    echo "Docker services running. Use './scripts/dev.sh health' to verify."
    ;;

  down)
    echo "Stopping Docker services..."
    cd "$PROJECT_ROOT"
    docker compose down
    echo "Services stopped."
    ;;

  restart)
    echo "Restarting Docker services..."
    cd "$PROJECT_ROOT"
    docker compose down
    docker compose up -d
    echo "Services restarted."
    ;;

  status)
    cd "$PROJECT_ROOT"
    docker compose ps
    ;;

  health)
    echo "Querying system health endpoints..."
    check() {
      name="$1"
      url="$2"
      code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 "$url" 2>/dev/null || echo "000")
      if [[ "$code" =~ ^(200|204|301|302)$ ]]; then
        echo -e "  \033[32m[OK]\033[0m      $name ($url) - HTTP $code"
      else
        echo -e "  \033[31m[OFFLINE]\033[0m $name ($url) - HTTP $code"
      fi
    }

    check "Spring Boot Actuator" "http://localhost:8080/actuator/health"
    check "Python Parser Service" "http://localhost:8000/health"
    check "Prometheus Probe" "http://localhost:9090/-/healthy"
    check "Grafana Health" "http://localhost:3001/api/health"
    check "Ollama Runtime" "http://localhost:11434/"
    check "Frontend Studio" "http://localhost:3000/"
    ;;

  test)
    echo "Running backend tests..."
    cd "$PROJECT_ROOT/backend"
    ./mvnw test

    echo "Running sandbox tests..."
    cd "$PROJECT_ROOT/test-sandbox"
    ./mvnw test jacoco:report

    echo "Building frontend bundle..."
    cd "$PROJECT_ROOT/frontend"
    npm run build

    echo -e "\n\033[32mAll verification tests passed successfully!\033[0m"
    ;;

  *)
    echo "Usage: ./scripts/dev.sh [up|down|restart|status|health|test]"
    exit 1
    ;;
esac
