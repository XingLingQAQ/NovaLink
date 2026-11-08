import { useCallback, useEffect, useRef, useState } from 'react';

export function useEntitySnapshot() {
  const [items, setItems] = useState([]);
  const generation = useRef(0);
  const active = useRef(true);
  useEffect(() => {
    const currentGeneration = generation;
    active.current = true;
    return () => { active.current = false; currentGeneration.current++; };
  }, []);
  const receive = useCallback((update) => {
    generation.current++;
    if (active.current) setItems(update);
  }, []);
  const beginRead = useCallback(() => {
    const request = ++generation.current;
    return (snapshot) => {
      if (active.current && generation.current === request) setItems(snapshot);
    };
  }, []);
  return [items, receive, beginRead];
}
