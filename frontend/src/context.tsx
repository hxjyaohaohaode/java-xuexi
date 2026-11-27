import { createContext, useContext } from 'react';
import type { Capabilities, Session, Workspace, WorkspaceEvent } from './types';
export type Route =
  | 'learn'
  | 'practice'
  | 'progress'
  | 'lab'
  | 'studio'
  | 'tutor'
  | 'memory'
  | 'settings'
  | 'content'
  | 'evaluation'
  | 'operations'
  | 'agents';
export const classroomScenes = ['read', 'map', 'practice', 'lab', 'review', 'workshop'] as const;
export type ClassroomScene = (typeof classroomScenes)[number];
export interface WorkspaceState {
  classroomScene?: ClassroomScene;
  classroomExercise?: string;
  openClassroom?: (conceptId: string, scene: ClassroomScene, exerciseId?: string) => void;
  workspace: Workspace;
  session: Session;
  capabilities: Capabilities;
  tick: number;
  refresh: () => void;
  events: WorkspaceEvent[];
  navigate: (route: Route, selection?: string) => void;
  selection: string;
  can: (capability: string) => boolean;
  endSession?: (notice?: string) => void;
}
export const WorkspaceContext = createContext<WorkspaceState | null>(null);
export function useWorkspace() {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error('Workspace context missing');
  return context;
}
