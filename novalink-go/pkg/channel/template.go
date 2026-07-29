package channel

import (
	"errors"
	"sync"
)

// Template represents a channel template that can be used to create channels.
type Template struct {
	ID            string
	DisplayName   string
	Scope         Scope
	Permission    string
	MaxCapacity   int
	AllowedWorlds []string
	Format        string
	// ParentID allows template inheritance
	ParentID string
}

// TemplateManager handles channel template operations.
type TemplateManager struct {
	templates map[string]*Template
	manager   *Manager
	mutex     sync.RWMutex
}

var (
	ErrTemplateNotFound    = errors.New("template not found")
	ErrTemplateExists      = errors.New("template already exists")
	ErrCircularInheritance = errors.New("circular template inheritance detected")
)

// NewTemplateManager creates a new TemplateManager.
func NewTemplateManager(manager *Manager) *TemplateManager {
	return &TemplateManager{
		templates: make(map[string]*Template),
		manager:   manager,
	}
}

// RegisterTemplate registers a new channel template.
func (tm *TemplateManager) RegisterTemplate(template *Template) error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	if _, exists := tm.templates[template.ID]; exists {
		return ErrTemplateExists
	}

	// Validate parent if specified
	if template.ParentID != "" {
		if _, exists := tm.templates[template.ParentID]; !exists {
			return ErrTemplateNotFound
		}

		// Check for circular inheritance
		if tm.hasCircularInheritance(template.ID, template.ParentID) {
			return ErrCircularInheritance
		}
	}

	tm.templates[template.ID] = template
	return nil
}

// hasCircularInheritance checks if adding a parent would create a cycle.
func (tm *TemplateManager) hasCircularInheritance(templateID, parentID string) bool {
	visited := make(map[string]bool)
	current := parentID

	for current != "" {
		if current == templateID {
			return true
		}
		if visited[current] {
			return true
		}
		visited[current] = true

		parent, exists := tm.templates[current]
		if !exists {
			break
		}
		current = parent.ParentID
	}

	return false
}

// UnregisterTemplate removes a template.
func (tm *TemplateManager) UnregisterTemplate(templateID string) error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	if _, exists := tm.templates[templateID]; !exists {
		return ErrTemplateNotFound
	}

	// Check if any templates inherit from this one
	for _, t := range tm.templates {
		if t.ParentID == templateID {
			return errors.New("cannot delete template: other templates inherit from it")
		}
	}

	delete(tm.templates, templateID)
	return nil
}

// GetTemplate returns a template by ID.
func (tm *TemplateManager) GetTemplate(templateID string) (*Template, error) {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	template, exists := tm.templates[templateID]
	if !exists {
		return nil, ErrTemplateNotFound
	}
	return template, nil
}

// GetAllTemplates returns all registered templates.
func (tm *TemplateManager) GetAllTemplates() []*Template {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	templates := make([]*Template, 0, len(tm.templates))
	for _, t := range tm.templates {
		templates = append(templates, t)
	}
	return templates
}

// ResolveTemplate resolves a template with all inherited properties.
func (tm *TemplateManager) ResolveTemplate(templateID string) (*Template, error) {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	template, exists := tm.templates[templateID]
	if !exists {
		return nil, ErrTemplateNotFound
	}

	// If no parent, return as-is
	if template.ParentID == "" {
		return template, nil
	}

	// Build inheritance chain
	chain := []*Template{template}
	current := template.ParentID

	for current != "" {
		parent, exists := tm.templates[current]
		if !exists {
			break
		}
		chain = append(chain, parent)
		current = parent.ParentID
	}

	// Merge from parent to child (child overrides parent)
	resolved := &Template{
		ID: templateID,
	}

	// Process from oldest ancestor to newest
	for i := len(chain) - 1; i >= 0; i-- {
		t := chain[i]
		if t.DisplayName != "" {
			resolved.DisplayName = t.DisplayName
		}
		if t.Scope != "" {
			resolved.Scope = t.Scope
		}
		if t.Permission != "" {
			resolved.Permission = t.Permission
		}
		if t.MaxCapacity > 0 {
			resolved.MaxCapacity = t.MaxCapacity
		}
		if len(t.AllowedWorlds) > 0 {
			resolved.AllowedWorlds = t.AllowedWorlds
		}
		if t.Format != "" {
			resolved.Format = t.Format
		}
	}

	return resolved, nil
}

// CreateChannelFromTemplate creates a channel using a template.
func (tm *TemplateManager) CreateChannelFromTemplate(templateID, channelID string, overrides *ChannelConfig) (*Channel, error) {
	resolved, err := tm.ResolveTemplate(templateID)
	if err != nil {
		return nil, err
	}

	// Start with resolved template values
	config := ChannelConfig{
		ID:            channelID,
		DisplayName:   resolved.DisplayName,
		Scope:         resolved.Scope,
		Permission:    resolved.Permission,
		MaxCapacity:   resolved.MaxCapacity,
		AllowedWorlds: resolved.AllowedWorlds,
		Format:        resolved.Format,
		TemplateID:    templateID,
	}

	// Apply overrides if provided
	if overrides != nil {
		if overrides.DisplayName != "" {
			config.DisplayName = overrides.DisplayName
		}
		if overrides.Scope != "" {
			config.Scope = overrides.Scope
		}
		if overrides.ClientID != "" {
			config.ClientID = overrides.ClientID
		}
		if overrides.Permission != "" {
			config.Permission = overrides.Permission
		}
		if overrides.MaxCapacity > 0 {
			config.MaxCapacity = overrides.MaxCapacity
		}
		if len(overrides.AllowedWorlds) > 0 {
			config.AllowedWorlds = overrides.AllowedWorlds
		}
		if overrides.Password != "" {
			config.Password = overrides.Password
		}
		if overrides.OwnerID != "" {
			config.OwnerID = overrides.OwnerID
		}
		if overrides.Format != "" {
			config.Format = overrides.Format
		}
	}

	return tm.manager.CreateChannel(config)
}

// LoadTemplatesFromConfig loads templates from configuration.
func (tm *TemplateManager) LoadTemplatesFromConfig(configs map[string]TemplateConfig) error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	// First pass: register all templates without parents
	for id, cfg := range configs {
		if cfg.ParentID == "" {
			template := &Template{
				ID:            id,
				DisplayName:   cfg.DisplayName,
				Scope:         Scope(cfg.Scope),
				Permission:    cfg.Permission,
				MaxCapacity:   cfg.MaxCapacity,
				AllowedWorlds: cfg.AllowedWorlds,
				Format:        cfg.Format,
			}
			tm.templates[id] = template
		}
	}

	// Second pass: register templates with parents
	for id, cfg := range configs {
		if cfg.ParentID != "" {
			if _, exists := tm.templates[cfg.ParentID]; !exists {
				return errors.New("parent template not found: " + cfg.ParentID)
			}
			template := &Template{
				ID:            id,
				DisplayName:   cfg.DisplayName,
				Scope:         Scope(cfg.Scope),
				Permission:    cfg.Permission,
				MaxCapacity:   cfg.MaxCapacity,
				AllowedWorlds: cfg.AllowedWorlds,
				Format:        cfg.Format,
				ParentID:      cfg.ParentID,
			}
			tm.templates[id] = template
		}
	}

	return nil
}

// TemplateConfig is used for loading templates from configuration files.
type TemplateConfig struct {
	DisplayName   string   `yaml:"display-name"`
	Scope         string   `yaml:"scope"`
	Permission    string   `yaml:"permission"`
	MaxCapacity   int      `yaml:"max-capacity"`
	AllowedWorlds []string `yaml:"allowed-worlds"`
	Format        string   `yaml:"format"`
	ParentID      string   `yaml:"parent"`
}

// GetTemplateInfo returns information about a template.
type TemplateInfo struct {
	ID            string
	DisplayName   string
	Scope         Scope
	Permission    string
	MaxCapacity   int
	AllowedWorlds []string
	Format        string
	ParentID      string
	ChildCount    int
}

// GetTemplateInfo returns detailed information about a template.
func (tm *TemplateManager) GetTemplateInfo(templateID string) (*TemplateInfo, error) {
	tm.mutex.RLock()
	defer tm.mutex.RUnlock()

	template, exists := tm.templates[templateID]
	if !exists {
		return nil, ErrTemplateNotFound
	}

	// Count children
	childCount := 0
	for _, t := range tm.templates {
		if t.ParentID == templateID {
			childCount++
		}
	}

	return &TemplateInfo{
		ID:            template.ID,
		DisplayName:   template.DisplayName,
		Scope:         template.Scope,
		Permission:    template.Permission,
		MaxCapacity:   template.MaxCapacity,
		AllowedWorlds: template.AllowedWorlds,
		Format:        template.Format,
		ParentID:      template.ParentID,
		ChildCount:    childCount,
	}, nil
}

// UpdateTemplate updates an existing template.
func (tm *TemplateManager) UpdateTemplate(templateID string, updates *Template) error {
	tm.mutex.Lock()
	defer tm.mutex.Unlock()

	template, exists := tm.templates[templateID]
	if !exists {
		return ErrTemplateNotFound
	}

	// Check for circular inheritance if parent is being changed
	if updates.ParentID != "" && updates.ParentID != template.ParentID {
		if tm.hasCircularInheritance(templateID, updates.ParentID) {
			return ErrCircularInheritance
		}
	}

	// Apply updates
	if updates.DisplayName != "" {
		template.DisplayName = updates.DisplayName
	}
	if updates.Scope != "" {
		template.Scope = updates.Scope
	}
	if updates.Permission != "" {
		template.Permission = updates.Permission
	}
	if updates.MaxCapacity > 0 {
		template.MaxCapacity = updates.MaxCapacity
	}
	if len(updates.AllowedWorlds) > 0 {
		template.AllowedWorlds = updates.AllowedWorlds
	}
	if updates.Format != "" {
		template.Format = updates.Format
	}
	if updates.ParentID != "" {
		template.ParentID = updates.ParentID
	}

	return nil
}
