#!/usr/bin/env bash
# Bootstrap: installs ArgoCD via Helm, then hands control to GitOps (App of Apps).
# Run once per cluster. After this script, ArgoCD manages everything from the repo.
#
# Usage:
#   ./bootstrap/install-argocd.sh [--env prod] [--repo https://github.com/org/repo.git]
#
# Requirements: kubectl, helm, git (configured with cluster access)

set -euo pipefail

# ── defaults ────────────────────────────────────────────────────────────────
ENV="${ENV:-dev}"
REPO_URL="${REPO_URL:-https://github.com/Gerkkk/LogsPipeline.git}"
ARGOCD_NAMESPACE="argocd"
ARGOCD_CHART_VERSION="7.7.0"   # argo/argo-cd chart version
ARGOCD_HELM_REPO="https://argoproj.github.io/argo-helm"

# parse flags
while [[ $# -gt 0 ]]; do
  case $1 in
    --env)      ENV="$2";      shift 2 ;;
    --repo)     REPO_URL="$2"; shift 2 ;;
    *)          echo "Unknown flag: $1"; exit 1 ;;
  esac
done

VALUES_FILE="infra/argocd/values.yaml"
if [[ "$ENV" == "prod" ]]; then
  VALUES_FILE="infra/argocd/values-prod.yaml"
fi

echo "==> [1/5] Adding Argo Helm repo"
helm repo add argo "$ARGOCD_HELM_REPO" --force-update
helm repo update

echo "==> [2/5] Installing ArgoCD (env=$ENV, chart=$ARGOCD_CHART_VERSION)"
helm upgrade --install argocd argo/argo-cd \
  --namespace "$ARGOCD_NAMESPACE" \
  --create-namespace \
  --version "$ARGOCD_CHART_VERSION" \
  --values "$VALUES_FILE" \
  --wait \
  --timeout 10m

echo "==> [3/5] Waiting for ArgoCD server to be ready"
kubectl rollout status deployment/argocd-server \
  -n "$ARGOCD_NAMESPACE" --timeout=300s

echo "==> [4/5] Applying root App of Apps"
# Patch repo URL into root-app if REPO_URL is overridden
if [[ -n "${REPO_URL:-}" ]]; then
  sed "s|REPO_URL_PLACEHOLDER|$REPO_URL|g" apps/root-app.yaml \
    | kubectl apply -f -
else
  kubectl apply -f apps/root-app.yaml
fi

echo "==> [5/5] Bootstrap complete"
echo ""
echo "ArgoCD is running. Access the UI:"
echo "  kubectl port-forward svc/argocd-server -n $ARGOCD_NAMESPACE 8080:443"
echo ""
echo "Initial admin password:"
echo "  kubectl get secret argocd-initial-admin-secret -n $ARGOCD_NAMESPACE \\"
echo "    -o jsonpath='{.data.password}' | base64 -d && echo"
echo ""
echo "ArgoCD will now sync all applications from: $REPO_URL"
