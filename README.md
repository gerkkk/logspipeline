# LogsPipeline

CI/CD pipeline for deploying heterogeneous applications to Kubernetes:
Spark jobs, third-party Helm charts, and a Node.js/React dashboard.

---

## Repository structure

```
LogsPipeline/
├── bootstrap/
│   └── install-argocd.sh          # One-time bootstrap: installs ArgoCD, then applies root app
│
├── infra/                          # Third-party product Helm chart wrappers
│   ├── argocd/                     # ArgoCD (self-managed after bootstrap)
│   │   ├── Chart.yaml              #   depends on argo/argo-cd
│   │   ├── values.yaml             #   dev defaults
│   │   └── values-prod.yaml        #   prod overrides (ingress, HA, TLS)
│   ├── minio/                      # MinIO object storage
│   │   ├── Chart.yaml              #   depends on minio/minio
│   │   ├── values.yaml
│   │   └── values-prod.yaml
│   ├── spark-operator/             # Kubeflow Spark Operator
│   │   ├── Chart.yaml
│   │   └── values.yaml
│   └── argo-workflows/             # Argo Workflows engine
│       ├── Chart.yaml
│       └── values.yaml
│
├── apps/
│   ├── root-app.yaml               # App of Apps — ArgoCD watches this directory
│   └── argocd-apps/                # One Application manifest per service
│       ├── argocd.yaml             #   self-managed ArgoCD
│       ├── minio.yaml
│       ├── spark-operator.yaml
│       ├── argo-workflows.yaml
│       └── dashboard.yaml
│
├── charts/
│   └── dashboard/                  # Internal React/Node.js showcase Helm chart
│       ├── Chart.yaml
│       ├── values.yaml
│       └── templates/
│           ├── _helpers.tpl
│           ├── deployment.yaml
│           ├── service.yaml
│           └── ingress.yaml
│
├── workflows/
│   ├── templates/
│   │   └── spark-job-template.yaml # Reusable WorkflowTemplate (templateRef target)
│   └── jobs/
│       └── example-spark-job.yaml  # Concrete Workflow that calls the template
│
├── overlays/                       # Kustomize environment overlays
│   ├── dev/kustomization.yaml      #   single replica, reduced resources
│   └── prod/kustomization.yaml     #   3 replicas, ingress, larger limits
│
├── spark/
│   └── jobs/
│       └── wordcount/
│           ├── main.py             # PySpark job source
│           └── Dockerfile          # built by CI, pushed to registry
│
└── frontend/
    ├── Dockerfile                  # multi-stage build (Node 20 → slim runtime)
    └── src/App.jsx                 # React dashboard skeleton
```

---

## Bootstrap (one-time, per cluster)

```bash
# Set the repo URL if it differs from the default
export REPO_URL=https://github.com/Gerkkk/LogsPipeline.git

# For dev
./bootstrap/install-argocd.sh

# For prod
./bootstrap/install-argocd.sh --env prod --repo "$REPO_URL"
```

The script:
1. Adds the Argo Helm repo and installs ArgoCD via `helm upgrade --install`
2. Waits for the ArgoCD server deployment to be ready
3. Applies `apps/root-app.yaml` — the **App of Apps** that points to `apps/argocd-apps/`

From this point **everything is GitOps**: a `git push` to `main` is the only deploy mechanism.

---

## GitOps flow (post-bootstrap)

```
git push → GitHub → ArgoCD polls repo (default: 3 min)
         → ArgoCD syncs apps/argocd-apps/*.yaml
         → Each Application syncs its chart from infra/ or charts/
         → Kubernetes applies the rendered manifests
```

Sync waves ensure correct ordering:
| Wave | Application |
|------|-------------|
| 1    | argocd (self-managed) |
| 2    | minio, spark-operator |
| 3    | argo-workflows (needs minio) |
| 4    | dashboard |

---

## CI pipeline (GitHub Actions / ArgoWF)

### Spark job image

```
git push spark/jobs/wordcount/
  → CI: docker build → push ghcr.io/gerkkk/logspipeline/spark-wordcount:<sha>
  → CI: update image tag in workflows/jobs/example-spark-job.yaml
  → git commit + push → ArgoCD syncs the Workflow resource
```

### Dashboard image

```
git push frontend/
  → CI: docker build → push ghcr.io/gerkkk/logspipeline/dashboard:<sha>
  → CI: helm upgrade --set image.tag=<sha> (or update charts/dashboard/values.yaml)
  → ArgoCD syncs charts/dashboard/ → rolling update in cluster
```

### Running a Spark job on demand

```bash
argo submit workflows/jobs/example-spark-job.yaml \
  -n argo-workflows \
  --parameter image=ghcr.io/gerkkk/logspipeline/spark-wordcount:<sha>
```

The workflow calls `templateRef: spark-job-template / submit-spark-job`, which creates a
`SparkApplication` resource — the Spark Operator runs the driver/executors and streams
event logs to MinIO (`s3a://spark-logs/`).

---

## Rollback

| Artifact | Rollback method |
|----------|----------------|
| Helm chart / values | `git revert` + push → ArgoCD re-syncs |
| Docker image | update `image.tag` in values.yaml + push |
| Infra chart version | change `version:` in `infra/*/Chart.yaml` + push |
| Argo Workflow definition | `git revert` + `argo submit` |
