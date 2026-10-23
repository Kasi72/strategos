// Builds app/src/main/assets/agent/* from agent/ (blueprint §7.8).
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..", "..");
const agentDir = join(repo, "agent");
const outDir = join(repo, "app", "src", "main", "assets", "agent");
const opengap = join(here, "node_modules", ".bin", "opengap");

function fail(message) {
  console.error(`export-agent: ${message}`);
  process.exit(1);
}

// 1. Validate.
try {
  execFileSync(opengap, ["validate"], { cwd: agentDir, stdio: "inherit" });
} catch {
  fail("opengap validate failed");
}

// 2. Export the system prompt.
mkdirSync(outDir, { recursive: true });
const promptPath = join(outDir, "system_prompt.md");
execFileSync(opengap, ["export", "-f", "system-prompt", "-d", agentDir, "-o", promptPath], { stdio: "inherit" });

// 3. One "## Skill: " heading per skill directory.
const prompt = readFileSync(promptPath, "utf8");
const skillDirs = readdirSync(join(agentDir, "skills")).filter((d) => statSync(join(agentDir, "skills", d)).isDirectory());
const skillHeadings = prompt.split("## Skill: ").length - 1;
if (skillHeadings !== skillDirs.length) {
  fail(`system_prompt.md has ${skillHeadings} "## Skill: " headings, expected ${skillDirs.length}`);
}

// 4. tools.json with "@change_op" expanded.
const changeOp = yaml.load(readFileSync(join(agentDir, "schemas", "change_op.yaml"), "utf8"));
const expand = (value) => {
  if (value === "@change_op") return structuredClone(changeOp);
  if (Array.isArray(value)) return value.map(expand);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, expand(v)]));
  }
  return value;
};
const toolFiles = readdirSync(join(agentDir, "tools")).filter((f) => f.endsWith(".yaml")).sort();
const tools = toolFiles.map((file) => {
  const def = yaml.load(readFileSync(join(agentDir, "tools", file), "utf8"));
  return { type: "function", name: def.name, description: def.description, parameters: expand(def.input_schema) };
});
const toolsJson = JSON.stringify(tools, null, 2) + "\n";
writeFileSync(join(outDir, "tools.json"), toolsJson);

// 5. agent_version.json (no timestamps, so the output is reproducible).
const sha256 = (text) => createHash("sha256").update(text).digest("hex");
const manifest = yaml.load(readFileSync(join(agentDir, "agent.yaml"), "utf8"));
const version = {
  agentVersion: String(manifest.version),
  toolsSha256: sha256(toolsJson),
  promptSha256: sha256(readFileSync(promptPath, "utf8")),
};
writeFileSync(join(outDir, "agent_version.json"), JSON.stringify(version, null, 2) + "\n");
console.log(`export-agent: ${tools.length} tools, ${skillDirs.length} skills, agent ${version.agentVersion}`);
