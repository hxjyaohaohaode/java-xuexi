export interface JourneyDriver {
  fill(label: string, value: string, scope?: string): Promise<void>;
  verifyPattern(label: string, accepted: string[], rejected: string[]): Promise<void>;
  route(): Promise<string>;
  click(role: string, name: string | RegExp, scope?: string): Promise<void>;
  select(label: string, value: string, scope?: string): Promise<void>;
  check(name: string | RegExp, scope?: string): Promise<void>;
  summary(name: string | RegExp, scope?: string): Promise<void>;
  text(text: string | RegExp, scope?: string): Promise<void>;
  heading(name: string | RegExp, scope?: string): Promise<void>;
  link(name: string, scope?: string): Promise<void>;
  disabled(role: string, name: string, scope?: string): Promise<void>;
  enabled(role: string, name: string, scope?: string): Promise<void>;
  value(label: string, scope?: string): Promise<string>;
  absentLabel(label: string): Promise<void>;
  clickRow(selector: string, text: string, button: string): Promise<void>;
  clickArtifact(id: string): Promise<void>;
  readJson(path: string): Promise<any>;
  mutate(path: string, method: string, body: unknown): Promise<any>;
  upload(
    label: string,
    files: { name: string; mimeType: string; buffer: Uint8Array }[],
  ): Promise<void>;
  report(name: string): Promise<any>;
  capture(name: string, scope?: string): Promise<void>;
  captureResponsive(
    prefix: string,
    widths: number[],
    scope?: string,
    systemTheme?: 'light' | 'dark',
  ): Promise<void>;
  eventually(work: () => Promise<boolean>, message: string): Promise<void>;
}
export function runWorkbenchJourney(
  driver: JourneyDriver,
  check: (name: string, work: () => Promise<void>) => Promise<void>,
): Promise<{ releaseId: string; suiteId: string; evaluationRunIds: string[]; notebookId: string }>;
export function docxFixture(): Buffer;
