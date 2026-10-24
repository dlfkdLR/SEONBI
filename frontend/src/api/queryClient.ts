import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./http";

/** Shared query behavior: do not retry client errors, retry transient failures twice. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status < 500) return false;
        return failureCount < 2;
      },
    },
    mutations: {
      retry: false,
    },
  },
});
