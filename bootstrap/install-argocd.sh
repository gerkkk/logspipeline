#!/usr/bin/env bash
# Bootstrap: installs ArgoCD via Helm with correct Redis image
# Run once per cluster.

set -euo pipefail

# ── defaults ────────────────────────────────────────────────────────────────
ENV="${ENV:-dev}"
REPO_URL="${REPO_URL:-https://github.com/Gerkkk/LogsPipeline.git}"
ARGOCD_NAMESPACE="argocd"
ARGOCD_CHART_VERSION="7.7.0"
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

echo "==> [1/4] Adding Argo Helm repo"
helm repo add argo "$ARGOCD_HELM_REPO" --force-update
helm repo update

echo "==> [2/4] Installing ArgoCD with correct Redis image (env=$ENV)"
helm upgrade --install argocd argo/argo-cd \
  --namespace "$ARGOCD_NAMESPACE" \
  --create-namespace \
  --version "$ARGOCD_CHART_VERSION" \
  --values "$VALUES_FILE" \
  --set redis.image.repository=redis \
  --set redis.image.tag=7.0.15-alpine \
  --set redis.image.pullPolicy=IfNotPresent \
  --wait \
  --timeout 10m

echo "==> [3/4] Applying root App of Apps"
sed "s|REPO_URL_PLACEHOLDER|$REPO_URL|g" apps/root-app.yaml | kubectl apply -f -

echo "==> [4/4] Bootstrap complete"
echo ""
echo "✅ ArgoCD is running. Access the UI:"
echo "   kubectl port-forward svc/argocd-server -n $ARGOCD_NAMESPACE 8080:443"
echo ""
echo "🔑 Initial admin password:"
echo "   kubectl get secret argocd-initial-admin-secret -n $ARGOCD_NAMESPACE \\"
echo "     -o jsonpath='{.data.password}' | base64 -d && echo"
echo ""
echo "📦 ArgoCD will sync applications from: $REPO_URL"