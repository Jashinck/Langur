import { API } from './endpoints';
import { http } from './http';
import type { Agent, CreateAgentInput } from './types';

export const listAgents = () => http.get<Agent[]>(API.agents);

export const createAgent = (input: CreateAgentInput) => http.post<Agent>(API.agents, input);

export const getAgent = (id: string) => http.get<Agent>(API.agent(id));

export const deleteAgent = (id: string) => http.delete<void>(API.agent(id));